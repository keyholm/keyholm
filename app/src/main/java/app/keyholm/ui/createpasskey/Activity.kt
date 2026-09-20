package app.keyholm.ui.createpasskey

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.AuthenticationRequest
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.provider.creationOffer
import app.keyholm.provider.preferRpName
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.ui.common.PRF_PROMPT_TITLE
import app.keyholm.ui.common.PromptResult
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.userLabel
import app.keyholm.ui.theme.KeyholmTheme
import app.keyholm.util.logger
import app.keyholm.webauthn.AttestationObject
import app.keyholm.webauthn.AttestationObjects
import app.keyholm.webauthn.AttestationResponse
import app.keyholm.webauthn.AuthenticatorData
import app.keyholm.webauthn.Caller
import app.keyholm.webauthn.ClientDataJson
import app.keyholm.webauthn.ClientDataType
import app.keyholm.webauthn.CreationOptions
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialResponseJson
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.DerSignature
import app.keyholm.webauthn.KeyAlias
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RegistrationPrf
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.SpkiPublicKey
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.WebAuthnAlgorithm
import com.google.protobuf.ByteString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant

private const val CREDENTIAL_ID_BYTES = 32

internal sealed interface Registration<out T> {
    data class Ready<T>(
        val value: T,
    ) : Registration<T>

    sealed interface Failed : Registration<Nothing> {
        val toastMessage: String?
    }

    data class Internal(
        override val toastMessage: String? = null,
    ) : Failed

    data class MalformedRequest(
        val domError: DomError,
        val detail: String,
    ) : Failed {
        override val toastMessage = ErrorMessages.MALFORMED_REQUEST
    }

    data object AlreadyRegistered : Failed {
        override val toastMessage = ErrorMessages.ALREADY_REGISTERED
    }

    data class NoCreateOption(
        override val toastMessage: String? = null,
    ) : Failed

    data class Interrupted(
        override val toastMessage: String? = null,
    ) : Failed

    data class Canceled(
        override val toastMessage: String? = null,
    ) : Failed
}

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

internal data class CreateRequest(
    val providerRequest: ProviderCreateCredentialRequest,
    val callingRequest: CreatePublicKeyCredentialRequest,
    val options: CreationOptions,
)

internal data class KeyMaterial(
    val authenticators: AuthenticatorPolicy,
    val generated: SecureKeyManager.GeneratedCredential,
    val authData: AuthenticatorData,
    val toSign: SigningInput,
    val signature: Signature,
    val prfSecurityLevel: KeySecurityLevel?,
)

internal data class RegistrantInfo(
    val rp: RelyingParty,
    val user: CredentialUser,
    val callingPackage: PackageName,
    val credPropsRequested: Boolean,
    val prfRequested: Boolean,
)

internal data class RegistrationContext(
    val info: RegistrantInfo,
    val challenge: String,
    val caller: Caller.Trusted,
    val algorithm: WebAuthnAlgorithm,
    val includeAttestation: Boolean,
    val identifyAsKeyholm: Boolean,
    val prfEvalSalts: PrfExtension.Salts?,
)

private data class PendingRegistration(
    val alias: KeyAlias,
    val credentialId: CredentialId,
    val info: RegistrantInfo,
    val clientDataJSON: ClientDataJson,
    val authData: AuthenticatorData,
    val toSign: SigningInput,
    val generated: SecureKeyManager.GeneratedCredential,
    val includeAttestation: Boolean,
    val prfSecurityLevel: KeySecurityLevel?,
    val prfEvalSalts: PrfExtension.Salts?,
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
        )
    }
}

internal fun registrationDescription(
    appLabel: String,
    algorithm: WebAuthnAlgorithm,
    includeAttestation: Boolean,
): String =
    buildString {
        append("$appLabel wants to create a device-bound ${algorithm.displayName} passkey")
        if (includeAttestation) {
            append(" with attestation, which reveals identifying information about your device")
        }
        append(":")
    }

internal fun registrationPromptContent(
    context: Context,
    info: RegistrantInfo,
    algorithm: WebAuthnAlgorithm,
    includeAttestation: Boolean,
    preferRpName: Boolean,
): AuthenticationRequest.BodyContent =
    promptContent(
        description =
            registrationDescription(
                context.appLabel(info.callingPackage),
                algorithm,
                includeAttestation,
            ),
        lastUsedAt = null,
        rp = info.rp,
        preferRpName = preferRpName,
        userLabel = userLabel(info.user.name, info.user.displayName),
    )

private fun buildPendingRegistration(
    registration: RegistrationContext,
    material: KeyMaterial,
    clientDataJSON: ClientDataJson,
    credentialId: CredentialId,
    alias: KeyAlias,
): PendingRegistration =
    PendingRegistration(
        alias = alias,
        credentialId = credentialId,
        info = registration.info,
        clientDataJSON = clientDataJSON,
        authData = material.authData,
        toSign = material.toSign,
        generated = material.generated,
        includeAttestation = registration.includeAttestation,
        prfSecurityLevel = material.prfSecurityLevel,
        prfEvalSalts = registration.prfEvalSalts,
    )

class Activity : FragmentActivity() {
    private val log = logger()
    private val cryptoPrompt = CryptoPrompt(this)
    private val passkeyRepo by lazy { PasskeyRepository(applicationContext) }
    private val keyMaterial by lazy {
        RegistrationKeyMaterial(applicationContext, passkeyRepo, MigrationRepository(applicationContext))
    }
    private val deniedAppsRepo by lazy { DeniedNativeAppRepository(applicationContext) }
    private val creationChoice = Channel<CreationChoice?>(Channel.CONFLATED)
    private val creationOptions = mutableStateOf<CreationOptionsPrompt?>(null)
    private val requestResolver by lazy {
        RegistrationRequestResolver(
            applicationContext,
            intent,
            passkeyRepo,
            deniedAppsRepo,
            RegistrationPrompts(
                applicationContext,
                intent,
                keyMaterial,
                cryptoPrompt,
                ::chooseCreationOptions,
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        setUpCreationOptionsSheet()
        lifecycleScope.launch { registerPasskey() }
    }

    private fun setUpCreationOptionsSheet() {
        setContent {
            KeyholmTheme {
                creationOptions.value?.let { prompt ->
                    CreationOptionsSheet(
                        prompt = prompt,
                        onChoice = {
                            creationOptions.value = null
                            creationChoice.trySend(it)
                        },
                        onDismiss = {
                            creationOptions.value = null
                            creationChoice.trySend(null)
                        },
                    )
                }
            }
        }
    }

    private suspend fun registerPasskey() {
        val offer = intent.creationOffer(applicationContext)
        val registration =
            requestResolver
                .prepareRegistration(offer)
                .orFail { failCreateCredential(it.toException(), it.toastMessage) } ?: return

        val cd =
            WebAuthn.clientData(
                type = ClientDataType.Create,
                challengeB64Url = registration.challenge,
                caller = registration.caller,
            )
        val clientDataJSON = cd.json
        val clientDataHash = cd.hash

        val credentialId = CredentialId.of(ByteArray(CREDENTIAL_ID_BYTES).also { SecureRandom().nextBytes(it) })
        val alias = credentialId.signingKeyAlias

        val material =
            keyMaterial
                .create(registration, credentialId, clientDataHash, offer.authenticators, offer.invalidateOnBiometricEnrollment)
                .orFail { failCreateCredential(it.toException(), it.toastMessage) } ?: return

        val pending = buildPendingRegistration(registration, material, clientDataJSON, credentialId, alias)

        when (val promptResult = promptForRegistration(registration, material)) {
            is PromptResult.Success -> {
                finishRegistration(pending, promptResult.crypto)
            }

            PromptResult.Canceled -> {
                keyMaterial.deleteKey(alias)
                if (material.prfSecurityLevel != null) keyMaterial.deleteKey(credentialId.hmacKeyAlias)
                cancelCreateCredential()
            }

            PromptResult.Failed, PromptResult.NoCryptoObject -> {
                keyMaterial.deleteKey(alias)
                if (material.prfSecurityLevel != null) keyMaterial.deleteKey(credentialId.hmacKeyAlias)
                failCreateCredential(CreateCredentialUnknownException())
            }
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
                    this,
                    registration.info,
                    registration.algorithm,
                    registration.includeAttestation,
                    intent.preferRpName(),
                ),
        )

    private suspend fun chooseCreationOptions(prompt: CreationOptionsPrompt): CreationChoice? {
        creationOptions.value = prompt
        return creationChoice.receive()
    }

    private suspend fun finishRegistration(
        pending: PendingRegistration,
        crypto: BiometricPrompt.CryptoObject,
    ) {
        val sig =
            crypto.signature
                ?: return failCreateCredential(CreateCredentialUnknownException(), ErrorMessages.CREATE_NO_SIGNATURE)

        val attestationSig =
            try {
                sig.update(pending.toSign.bytes)
                DerSignature(sig.sign())
            } catch (e: GeneralSecurityException) {
                log.e(e) { "signing failed" }
                keyMaterial.deleteKey(pending.alias)
                if (pending.prfSecurityLevel != null) keyMaterial.deleteKey(pending.credentialId.hmacKeyAlias)
                return failCreateCredential(CreateCredentialUnknownException(), ErrorMessages.SIGN_FAILED_CREATE)
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

        val persisted = keyMaterial.persist(pending.toPasskeyRecord())
        when (persisted) {
            is Registration.Failed -> {
                keyMaterial.deleteKey(pending.alias)
                if (pending.prfSecurityLevel != null) keyMaterial.deleteKey(pending.credentialId.hmacKeyAlias)
                failCreateCredential(persisted.toException(), persisted.toastMessage)
            }

            is Registration.Ready -> {
                evaluatePrfAndRespond(pending, attestationObject)
            }
        }
    }

    private suspend fun evaluatePrfAndRespond(
        pending: PendingRegistration,
        attestationObject: AttestationObject,
    ) {
        if (pending.prfSecurityLevel == null) {
            respondToRegistration(pending, attestationObject, RegistrationPrf.NotRequested)
            return
        }
        val salts = pending.prfEvalSalts
        if (salts == null) {
            respondToRegistration(pending, attestationObject, RegistrationPrf.Requested)
            return
        }
        val prfAlias = pending.credentialId.hmacKeyAlias

        val mac =
            when (val r = keyMaterial.macFor(prfAlias)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return failCreateCredential(CreateCredentialUnknownException(), r.toastMessage)
            }
        val hmacAuthenticators =
            when (val r = keyMaterial.hmacAuthenticatorsFor(prfAlias)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return failCreateCredential(CreateCredentialUnknownException(), r.toastMessage)
            }
        val authorized =
            cryptoPrompt.authenticate(
                title = PRF_PROMPT_TITLE,
                cryptoObject = BiometricPrompt.CryptoObject(mac),
                allowedAuthenticators = hmacAuthenticators,
                content =
                    promptContent(
                        description = "${appLabel(pending.info.callingPackage)} wants access to the secret for:",
                        lastUsedAt = null,
                        rp = pending.info.rp,
                        preferRpName = intent.preferRpName(),
                        userLabel = userLabel(pending.info.user.name, pending.info.user.displayName),
                    ),
            ) as? PromptResult.Success
        val results = authorized?.crypto?.mac?.let { PrfExtension.evaluate(it, salts) }
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
                prf = prf,
            )

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(responseJson))
        setResult(RESULT_OK, result)
        finish()
    }
}
