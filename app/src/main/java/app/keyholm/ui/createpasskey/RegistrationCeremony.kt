package app.keyholm.ui.createpasskey

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import app.keyholm.authenticator.KeyAccess
import app.keyholm.ctap.AuthenticatorData
import app.keyholm.ctap.CredentialId
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.PasskeyKeyManager
import app.keyholm.provider.CreationOffer
import app.keyholm.provider.creationOffer
import app.keyholm.provider.preferRpName
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.PrfPrompt
import app.keyholm.ui.common.PromptResult
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.prfPromptContent
import app.keyholm.util.logger
import app.keyholm.webauthn.AttestationObject
import app.keyholm.webauthn.AttestationObjects
import app.keyholm.webauthn.AttestationResponse
import app.keyholm.webauthn.ClientDataJson
import app.keyholm.webauthn.ClientDataType
import app.keyholm.webauthn.CredentialResponseJson
import app.keyholm.webauthn.DerSignature
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RegistrationPrf
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.SpkiPublicKey
import app.keyholm.webauthn.WebAuthn
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant

private const val CREDENTIAL_ID_BYTES = 32

private fun Registration.Failed.toException(): CreateCredentialException =
    when (this) {
        is Registration.Internal -> {
            CreateCredentialUnknownException()
        }

        is Registration.MalformedRequest -> {
            CreatePublicKeyCredentialDomException(domError, detail)
        }

        is Registration.AlreadyRegistered -> {
            CreatePublicKeyCredentialDomException(InvalidStateError(), "a passkey for this account already exists")
        }

        is Registration.NoCreateOption -> {
            CreateCredentialNoCreateOptionException()
        }

        is Registration.Interrupted -> {
            CreateCredentialInterruptedException()
        }

        is Registration.Canceled -> {
            CreateCredentialCancellationException()
        }
    }

private inline fun <T> Registration<T>.orFail(onFailed: (Registration.Failed) -> Unit): T? =
    when (this) {
        is Registration.Ready -> {
            value
        }

        is Registration.Failed -> {
            onFailed(this)
            null
        }
    }

private data class PendingRegistration(
    val credentialId: CredentialId,
    val info: RegistrantInfo,
    val clientDataJSON: ClientDataJson,
    val authData: AuthenticatorData,
    val toSign: SigningInput,
    val generated: PasskeyKeyManager.GeneratedCredential,
    val includeAttestation: Boolean,
    val prfSecurityLevel: KeySecurityLevel?,
    val prfEvalInputs: PrfExtension.Inputs?,
) {
    fun toPasskeyRecord(): PasskeyRecord {
        val now = Instant.now()
        return PasskeyRecord(
            credentialId = credentialId,
            rp = info.rp,
            user = info.user,
            signCount = 0,
            callingPackage = info.callingPackage,
            createdAt = now,
            keystore =
                PasskeyRecord.Keystore(
                    coseAlgorithm = generated.coseAlgorithm,
                    securityLevel = generated.securityLevel,
                    publicKeySpki = ByteString.copyFrom(generated.publicKey.encoded),
                    prfSecurityLevel = prfSecurityLevel,
                ),
            lifecycle = RecordLifecycle.Active,
            lastUsedAt = now,
            likelyInvalid = false,
            discoverable = info.discoverable,
        )
    }
}

private fun buildPendingRegistration(
    registration: RegistrationContext,
    material: KeyMaterial,
    clientDataJSON: ClientDataJson,
    credentialId: CredentialId,
): PendingRegistration =
    PendingRegistration(
        credentialId = credentialId,
        info = registration.info,
        clientDataJSON = clientDataJSON,
        authData = material.authData,
        toSign = material.toSign,
        generated = material.generated,
        includeAttestation = registration.includeAttestation,
        prfSecurityLevel = material.prfSecurityLevel,
        prfEvalInputs = registration.prfEvalInputs,
    )

internal class RegistrationCeremony(
    private val activity: FragmentActivity,
    private val dispatcher: CoroutineDispatcher,
    private val keyMaterial: RegistrationKeyMaterial,
    private val cryptoPrompt: CryptoPrompt,
    private val prompts: RegistrationPrompts,
    private val requestResolver: RegistrationRequestResolver,
) {
    private val log = logger()
    private val prfPrompt = PrfPrompt(cryptoPrompt, dispatcher)

    val working = mutableStateOf(false)

    suspend fun run() {
        val offer = activity.intent.creationOffer(activity.applicationContext, dispatcher)
        val registration =
            requestResolver
                .prepareRegistration(offer)
                .orFail { activity.failCreateCredential(it.toException(), it.toastMessage) } ?: return

        val cd =
            WebAuthn.clientData(
                type = ClientDataType.Create,
                challengeB64Url = registration.challenge,
                caller = registration.caller,
            )
        val credentialId = CredentialId.of(ByteArray(CREDENTIAL_ID_BYTES).also { SecureRandom().nextBytes(it) })
        createPasskey(offer, registration, cd, credentialId)
    }

    private suspend fun createPasskey(
        offer: CreationOffer,
        registration: RegistrationContext,
        cd: WebAuthn.ClientData,
        credentialId: CredentialId,
    ) {
        working.value = true
        val generated =
            try {
                keyMaterial.create(registration, credentialId, cd.hash, offer.authenticators, offer.invalidateOnBiometricEnrollment)
            } finally {
                working.value = false
            }
        val created = generated.orFail { activity.failCreateCredential(it.toException(), it.toastMessage) } ?: return
        val material =
            when (created) {
                is KeyCreation.Created -> created.material
                KeyCreation.DevicePropertiesUnavailable -> return retryWithoutDeviceProperties(offer, registration, cd, credentialId)
            }

        val pending = buildPendingRegistration(registration, material, cd.json, credentialId)
        val record = pending.toPasskeyRecord()

        var attestationObject: AttestationObject? = null
        try {
            attestationObject = authorizeAndPersist(registration, material, pending, record)
        } finally {
            if (attestationObject == null) {
                withContext(NonCancellable) { keyMaterial.discard(credentialId) }
            }
        }
        attestationObject?.let { evaluatePrfAndRespond(pending, record, it) }
    }

    private suspend fun authorizeAndPersist(
        registration: RegistrationContext,
        material: KeyMaterial,
        pending: PendingRegistration,
        record: PasskeyRecord,
    ): AttestationObject? =
        when (val promptResult = promptForRegistration(registration, material)) {
            is PromptResult.Success -> {
                signAndPersist(pending, record, promptResult.crypto)
            }

            PromptResult.Canceled -> {
                activity.cancelCreateCredential()
                null
            }

            PromptResult.Interrupted -> {
                activity.failCreateCredential(CreateCredentialInterruptedException())
                null
            }

            PromptResult.Failed, PromptResult.NoCryptoObject -> {
                activity.failCreateCredential(CreateCredentialUnknownException())
                null
            }
        }

    private suspend fun promptForRegistration(
        registration: RegistrationContext,
        material: KeyMaterial,
    ): PromptResult =
        cryptoPrompt.authenticate(
            title = "Create passkey",
            cryptoObject = BiometricPrompt.CryptoObject(material.signature),
            allowedAuthenticators = material.authenticators,
            content =
                registrationPromptContent(
                    activity,
                    registration.info,
                    registration.algorithm,
                    registration.includeAttestation,
                    activity.intent.preferRpName(),
                ),
        )

    private suspend fun retryWithoutDeviceProperties(
        offer: CreationOffer,
        registration: RegistrationContext,
        cd: WebAuthn.ClientData,
        credentialId: CredentialId,
    ) {
        Toast.makeText(activity, ErrorMessages.DEVICE_PROPERTIES_UNSUPPORTED, Toast.LENGTH_LONG).show()
        val choice = prompts.chooseWithoutDeviceProperties(offer, registration) ?: return activity.cancelCreateCredential()
        createPasskey(
            offer,
            registration.copy(
                algorithm = choice.algorithm,
                includeAttestation = choice.includeAttestation,
                includeDeviceProperties = choice.includeDeviceProperties,
                identifyAsKeyholm = choice.identifyAsKeyholm,
            ),
            cd,
            credentialId,
        )
    }

    private suspend fun signAndPersist(
        pending: PendingRegistration,
        record: PasskeyRecord,
        crypto: BiometricPrompt.CryptoObject,
    ): AttestationObject? {
        val sig = crypto.signature
        if (sig == null) {
            activity.failCreateCredential(CreateCredentialUnknownException(), ErrorMessages.CREATE_NO_SIGNATURE)
            return null
        }

        val attestationSig =
            try {
                withContext(dispatcher) {
                    sig.update(pending.toSign.bytes)
                    DerSignature(sig.sign())
                }
            } catch (e: GeneralSecurityException) {
                log.e(e) { "signing failed" }
                activity.failCreateCredential(CreateCredentialUnknownException(), ErrorMessages.SIGN_FAILED_CREATE)
                return null
            }

        val attestationObject =
            if (pending.generated.attestationChain.isNotEmpty() && pending.includeAttestation) {
                AttestationObjects.attestationObjectAndroidKey(
                    pending.authData,
                    attestationSig,
                    pending.generated.attestationChain,
                    pending.generated.coseAlgorithm.coseAlg,
                )
            } else {
                AttestationObjects.attestationObjectNone(pending.authData)
            }

        return when (val persisted = keyMaterial.persist(record)) {
            is Registration.Failed -> {
                activity.failCreateCredential(persisted.toException(), persisted.toastMessage)
                null
            }

            is Registration.Ready -> {
                attestationObject
            }
        }
    }

    private suspend fun evaluatePrfAndRespond(
        pending: PendingRegistration,
        record: PasskeyRecord,
        attestationObject: AttestationObject,
    ) {
        if (pending.prfSecurityLevel == null) {
            respondToRegistration(pending, attestationObject, RegistrationPrf.NotRequested)
            return
        }
        val inputs = pending.prfEvalInputs
        if (inputs == null) {
            respondToRegistration(pending, attestationObject, RegistrationPrf.Requested)
            return
        }

        val results =
            when (
                val r =
                    prfPrompt.evaluate(
                        macFor = { keyMaterial.assertionKeys.macFor(record) },
                        authenticatorsFor = { keyMaterial.assertionKeys.hmacAuthenticatorsFor(record) },
                        salts = PrfExtension.salts(inputs),
                        content =
                            prfPromptContent(
                                requester = activity.appLabel(pending.info.callingPackage),
                                declineAction = "create the passkey anyway",
                                rp = pending.info.rp,
                                user = pending.info.user,
                                preferRpName = activity.intent.preferRpName(),
                            ),
                    )
            ) {
                is KeyAccess.Failure -> {
                    Toast.makeText(activity, ErrorMessages.PRF_UNAVAILABLE_AFTER_CREATE, Toast.LENGTH_LONG).show()
                    null
                }

                is KeyAccess.Success -> {
                    r.value
                }
            }
        val outcome = results?.let(RegistrationPrf::Evaluated) ?: RegistrationPrf.Requested
        respondToRegistration(pending, attestationObject, outcome)
    }

    private fun respondToRegistration(
        pending: PendingRegistration,
        attestationObject: AttestationObject,
        prf: RegistrationPrf,
    ) {
        val responseJson =
            CredentialResponseJson.registrationResponseJson(
                credentialId = pending.credentialId,
                response =
                    AttestationResponse(
                        clientDataJSON = pending.clientDataJSON,
                        attestationObject = attestationObject,
                        authData = pending.authData,
                        spkiPublicKey = SpkiPublicKey(pending.generated.publicKey.encoded),
                        algorithm = pending.generated.coseAlgorithm,
                    ),
                credPropsRequested = pending.info.credPropsRequested,
                discoverable = pending.info.discoverable,
                prf = prf,
            )

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(responseJson))
        activity.setResult(Activity.RESULT_OK, result)
        activity.finish()
    }
}
