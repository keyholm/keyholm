package app.keyholm.ctap.handlers

import android.content.Context
import androidx.biometric.BiometricPrompt
import app.keyholm.authenticator.AlgorithmNegotiation
import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.CredentialKeyCreation
import app.keyholm.authenticator.CredentialKeyMaterial
import app.keyholm.authenticator.CredentialKeys
import app.keyholm.authenticator.availableFamilies
import app.keyholm.authenticator.availableMlDsaSupport
import app.keyholm.ctap.AuthenticatorData
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Response
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.InternalFailure
import app.keyholm.ctap.KEYHOLM_AAGUID
import app.keyholm.ctap.WebAuthnAlgorithm
import app.keyholm.ctap.ZERO_AAGUID
import app.keyholm.ctap.requests.MakeCredentialRequest
import app.keyholm.ctap.requests.parseMakeCredential
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.PasskeyKeyManager
import app.keyholm.settings.Settings
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.store.anyExcluded
import app.keyholm.store.readStore
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.PromptResult
import app.keyholm.ui.common.resolveCreateAuthenticators
import app.keyholm.ui.createpasskey.CreationChoice
import app.keyholm.ui.createpasskey.CreationOptionsChooser
import app.keyholm.ui.createpasskey.CreationOptionsPrompt
import app.keyholm.ui.createpasskey.OptionChoice
import app.keyholm.ui.createpasskey.attestationChoice
import app.keyholm.ui.createpasskey.chooseIfNeeded
import app.keyholm.ui.createpasskey.identityChoice
import app.keyholm.ui.createpasskey.registrationPromptContent
import app.keyholm.ui.pxp.CtapUi
import app.keyholm.util.logger
import com.google.protobuf.ByteString
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant

internal const val CREDENTIAL_ID_BYTES = 32
private const val ATTESTATION_NONE = "none"
internal const val ATTESTATION_ANDROID_KEY = "android-key"

private const val MC_RESPONSE_FMT = 1
private const val MC_RESPONSE_AUTH_DATA = 2
private const val MC_RESPONSE_ATT_STMT = 3
private const val MC_RESPONSE_UNSIGNED_EXTENSION_OUTPUTS = 6

private class PendingCredential(
    val credentialId: CredentialId,
    val choice: CreationChoice,
    val keys: CredentialKeys,
    val authData: AuthenticatorData,
    val authenticators: AuthenticatorPolicy,
)

private class SavedCredential(
    val record: PasskeyRecord,
    val attestation: ByteArray,
)

internal class MakeCredentialHandler(
    private val context: Context,
    private val settings: Settings,
    private val passkeyRepo: PasskeyRepository,
    private val ui: CtapUi,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()
    private val keys = CredentialKeyMaterial(dispatcher)
    private val chooseCreationOptions: CreationOptionsChooser = ui.choices.creationOptions::ask
    private val prf = PrfEvaluator(ui.prompts, AssertionKeyMaterial(passkeyRepo, dispatcher), settings.preferRpName, dispatcher)

    suspend fun handle(body: ByteArray): Ctap2Response =
        when (val parsed = parseMakeCredential(body)) {
            is Ctap2Result.Rejected -> Ctap2Response(parsed.status)
            is Ctap2Result.Ok -> handle(parsed.value)
        }

    private suspend fun handle(request: MakeCredentialRequest): Ctap2Response {
        val authenticators =
            when (val resolution = resolveCreateAuthenticators(context, settings.createAuthenticators)) {
                is AuthenticatorsResolution.Unavailable -> {
                    log.w { resolution.message }
                    return Ctap2Response(Ctap2Status.OPERATION_DENIED)
                }

                is AuthenticatorsResolution.Ready -> {
                    resolution.value
                }
            }
        val algorithms = offeredAlgorithms(request.algorithms)
        if (algorithms.isEmpty()) return Ctap2Response(Ctap2Status.UNSUPPORTED_ALGORITHM)
        val options =
            CreationOptionsPrompt(
                rpId = request.info.rp.id,
                userName = request.info.user.name,
                algorithms = algorithms,
                attestation = request.attestation.attestationChoice(),
                identity = settings.identityPreference.identityChoice(),
                devicePropertiesAvailable = true,
            )
        val choice =
            chooseCreationOptions.chooseIfNeeded(options)
                ?: return Ctap2Response(Ctap2Status.OPERATION_DENIED)
        return when (excluded(request)) {
            null -> {
                ui.report(InternalFailure.Read)
                Ctap2Response(Ctap2Status.OTHER)
            }

            true -> {
                confirmExcluded(request, choice, authenticators)
            }

            false -> {
                when (val created = create(request, options, choice, authenticators)) {
                    is Ctap2Result.Rejected -> Ctap2Response(created.status)
                    is Ctap2Result.Ok -> authorizeOrDiscard(request, created.value)
                }
            }
        }
    }

    private suspend fun confirmExcluded(
        request: MakeCredentialRequest,
        choice: CreationChoice,
        authenticators: AuthenticatorPolicy,
    ): Ctap2Response {
        ui.prompts.confirm(
            title = "Create passkey",
            allowedAuthenticators = authenticators,
            content = registrationPromptContent(context, request.info, choice.algorithm, choice.includeAttestation, settings.preferRpName),
        )
        return Ctap2Response(Ctap2Status.CREDENTIAL_EXCLUDED)
    }

    private suspend fun authorizeOrDiscard(
        request: MakeCredentialRequest,
        pending: PendingCredential,
    ): Ctap2Response {
        var saved: Ctap2Result<SavedCredential>? = null
        try {
            saved = authorize(request, pending)
        } finally {
            if (saved !is Ctap2Result.Ok) {
                withContext(NonCancellable) { keys.discard(pending.credentialId) }
            }
        }
        return when (saved) {
            is Ctap2Result.Rejected -> Ctap2Response(saved.status)
            is Ctap2Result.Ok -> respond(request, pending, saved.value)
        }
    }

    private suspend fun create(
        request: MakeCredentialRequest,
        options: CreationOptionsPrompt,
        choice: CreationChoice,
        authenticators: AuthenticatorPolicy,
    ): Ctap2Result<PendingCredential> {
        val credentialId = CredentialId.of(ByteArray(CREDENTIAL_ID_BYTES).also(SecureRandom()::nextBytes))
        val created =
            keys.create(
                PasskeyKeyManager.CredentialKeyRequest(
                    credentialId = credentialId,
                    attestationChallenge = request.clientDataHash,
                    algorithm = choice.algorithm,
                    authenticators = authenticators,
                    invalidateOnBiometricEnrollment = settings.invalidateOnBiometricEnrollment,
                    includeDeviceProperties = choice.includeDeviceProperties,
                ),
                prfRequested = request.info.prfRequested,
            )
        return when (created) {
            is CredentialKeyCreation.Created -> {
                Ctap2Result.Ok(pendingCredential(request, credentialId, choice, created.keys, authenticators))
            }

            CredentialKeyCreation.DevicePropertiesUnavailable -> {
                log.w { "device properties attestation is unavailable" }
                val retry =
                    options.copy(
                        attestation = OptionChoice.Ask(initial = choice.includeAttestation),
                        identity =
                            if (options.identity is OptionChoice.Ask) {
                                OptionChoice.Ask(initial = choice.identifyAsKeyholm)
                            } else {
                                options.identity
                            },
                        devicePropertiesAvailable = false,
                    )
                val retryChoice =
                    chooseCreationOptions.chooseIfNeeded(retry)
                        ?: return Ctap2Result.Rejected(Ctap2Status.OPERATION_DENIED)
                create(request, retry, retryChoice, authenticators)
            }

            is CredentialKeyCreation.Failed -> {
                ui.report(InternalFailure.Creating(created.failure))
                Ctap2Result.Rejected(Ctap2Status.OTHER)
            }
        }
    }

    private suspend fun authorize(
        request: MakeCredentialRequest,
        pending: PendingCredential,
    ): Ctap2Result<SavedCredential> {
        val result =
            ui.prompts.authenticate(
                title = "Create passkey",
                cryptoObject = BiometricPrompt.CryptoObject(pending.keys.signature),
                allowedAuthenticators = pending.authenticators,
                content =
                    registrationPromptContent(
                        context,
                        request.info,
                        pending.choice.algorithm,
                        pending.choice.includeAttestation,
                        settings.preferRpName,
                    ),
            )
        val signature = (result as? PromptResult.Success)?.crypto?.signature
        return if (signature == null) {
            log.w { "the credential was not authorised" }
            Ctap2Result.Rejected(Ctap2Status.OPERATION_DENIED)
        } else {
            save(request, pending, signature)
        }
    }

    private suspend fun save(
        request: MakeCredentialRequest,
        pending: PendingCredential,
        signature: Signature,
    ): Ctap2Result<SavedCredential> {
        val attestation =
            runCatching {
                withContext(dispatcher) {
                    signature.update(pending.authData.bytes + request.clientDataHash.bytes)
                    signature.sign()
                }
            }.getOrElse {
                log.e(it) { "signing failed" }
                return Ctap2Result.Rejected(Ctap2Status.OTHER)
            }
        val record = record(request, pending)
        return passkeyRepo.put(record).fold(
            onSuccess = { Ctap2Result.Ok(SavedCredential(record, attestation)) },
            onFailure = {
                log.e(it) { "saving the passkey failed" }
                ui.report(InternalFailure.Write)
                Ctap2Result.Rejected(Ctap2Status.OTHER)
            },
        )
    }

    private suspend fun respond(
        request: MakeCredentialRequest,
        pending: PendingCredential,
        saved: SavedCredential,
    ): Ctap2Response {
        val extensions =
            if (request.info.prfRequested) {
                val results = request.prfEval?.let { salts -> prf.evaluate(saved.record, salts) }
                prfOutput(enabled = true, results = results)
            } else {
                null
            }
        return Ctap2Response(attestationObject(pending, saved.attestation, extensions))
    }

    private fun offeredAlgorithms(requested: List<WebAuthnAlgorithm>): List<WebAuthnAlgorithm> {
        val enabled = availableFamilies(context, settings.enabledFamilies)
        val mlDsa = availableMlDsaSupport(context, settings.mlDsaSupport)
        return AlgorithmNegotiation
            .offeredAlgorithms(
                requested,
                enabled,
                settings.preferredAlgorithm,
                settings.fallbackAlgorithm,
                mlDsa,
            )
    }

    private suspend fun excluded(request: MakeCredentialRequest): Boolean? {
        if (request.excludeIds.isEmpty()) return false
        val stored = readStore { passkeyRepo.activePasskeys.first() } ?: return null
        return stored.anyExcluded(request.info.rp.id, request.excludeIds)
    }
}

private fun attestationObject(
    pending: PendingCredential,
    signature: ByteArray,
    unsignedExtensions: CBORObject?,
): CBORObject {
    val withChain =
        pending.choice.includeAttestation &&
            pending.keys.generated.attestationChain
                .isNotEmpty()
    val attStmt = CBORObject.NewMap()
    if (withChain) {
        val x5c = CBORObject.NewArray()
        pending.keys.generated.attestationChain
            .forEach { x5c.Add(it) }
        attStmt.Add("alg", pending.keys.generated.coseAlgorithm.coseAlg).Add("sig", signature).Add("x5c", x5c)
    }
    return CBORObject
        .NewMap()
        .Add(MC_RESPONSE_FMT, if (withChain) ATTESTATION_ANDROID_KEY else ATTESTATION_NONE)
        .Add(MC_RESPONSE_AUTH_DATA, pending.authData.bytes)
        .Add(MC_RESPONSE_ATT_STMT, attStmt)
        .apply { unsignedExtensions?.let { Add(MC_RESPONSE_UNSIGNED_EXTENSION_OUTPUTS, it) } }
}

private fun record(
    request: MakeCredentialRequest,
    pending: PendingCredential,
): PasskeyRecord {
    val now = Instant.now()
    return PasskeyRecord(
        credentialId = pending.credentialId,
        rp = request.info.rp,
        user = request.info.user,
        signCount = 0,
        requester = request.info.requester,
        createdAt = now,
        keystore =
            PasskeyRecord.Keystore(
                coseAlgorithm = pending.keys.generated.coseAlgorithm,
                securityLevel = pending.keys.generated.securityLevel,
                publicKeySpki = ByteString.copyFrom(pending.keys.generated.publicKey.encoded),
                prfSecurityLevel = pending.keys.prfSecurityLevel,
            ),
        lifecycle = RecordLifecycle.Active,
        lastUsedAt = now,
        likelyInvalid = false,
        discoverable = request.info.discoverable,
    )
}

private fun pendingCredential(
    request: MakeCredentialRequest,
    credentialId: CredentialId,
    choice: CreationChoice,
    keys: CredentialKeys,
    authenticators: AuthenticatorPolicy,
): PendingCredential {
    val authData =
        AuthenticatorData.forRegistration(
            request.info.rp.id,
            credentialId,
            keys.generated.publicKey,
            if (choice.identifyAsKeyholm) KEYHOLM_AAGUID else ZERO_AAGUID,
            choice.algorithm,
        )
    return PendingCredential(
        credentialId = credentialId,
        choice = choice,
        keys = keys,
        authData = authData,
        authenticators = authenticators,
    )
}
