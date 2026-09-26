package app.keyholm.ui.createpasskey

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.SecureElementUnavailableException
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.ui.common.resolveCreateAuthenticators
import app.keyholm.util.logger
import app.keyholm.webauthn.ClientDataHash
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.KeyAlias
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.WebAuthnAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature
import javax.crypto.Mac

internal class RegistrationKeyMaterial(
    private val context: Context,
    private val passkeyRepo: PasskeyRepository,
    private val migrationRepo: MigrationRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    private val keyManager by lazy { SecureKeyManager() }
    private val log = logger()

    suspend fun deleteKey(alias: KeyAlias) = withContext(dispatcher) { keyManager.deleteKey(alias) }

    suspend fun resolveAuthenticators(policy: AuthenticatorPolicy): Registration<AuthenticatorPolicy> =
        when (val r = withContext(dispatcher) { resolveCreateAuthenticators(context, policy) }) {
            is AuthenticatorsResolution.Ready -> Registration.Ready(r.value)
            is AuthenticatorsResolution.Unavailable -> Registration.NoCreateOption(r.message)
        }

    suspend fun create(
        registration: RegistrationContext,
        credentialId: CredentialId,
        clientDataHash: ClientDataHash,
        createAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): Registration<KeyCreation> {
        val authenticators =
            when (val r = resolveAuthenticators(createAuthenticators)) {
                is Registration.Failed -> return r
                is Registration.Ready -> r.value
            }
        val request =
            SecureKeyManager.CredentialKeyRequest(
                alias = credentialId.signingKeyAlias,
                attestationChallenge = clientDataHash,
                algorithm = registration.algorithm,
                authenticators = authenticators,
                invalidateOnBiometricEnrollment = invalidateOnBiometricEnrollment,
                includeDeviceProperties = registration.includeDeviceProperties,
            )
        val generated =
            when (val r = generateKey(request)) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        return when (generated) {
            is SecureKeyManager.CredentialKeyGeneration.Generated -> {
                keyMaterialFor(registration, credentialId, clientDataHash, request, generated.credential)
            }

            SecureKeyManager.CredentialKeyGeneration.DevicePropertiesUnavailable -> {
                Registration.Ready(KeyCreation.DevicePropertiesUnavailable)
            }
        }
    }

    private suspend fun keyMaterialFor(
        registration: RegistrationContext,
        credentialId: CredentialId,
        clientDataHash: ClientDataHash,
        request: SecureKeyManager.CredentialKeyRequest,
        generated: SecureKeyManager.GeneratedCredential,
    ): Registration<KeyCreation> {
        val aaguid = if (registration.identifyAsKeyholm) WebAuthn.KEYHOLM_AAGUID else WebAuthn.ZERO_AAGUID
        val authData =
            WebAuthn.registrationAuthData(registration.info.rp.id, credentialId, generated.publicKey, aaguid, request.algorithm)
        val toSign = SigningInput(authData.bytes + clientDataHash.bytes)
        val signature =
            when (val r = signFor(request.alias, request.algorithm)) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        val prfSecurityLevel =
            when (
                val r =
                    generatePrfKey(
                        credentialId,
                        request.authenticators,
                        request.invalidateOnBiometricEnrollment,
                        registration.info.prfRequested,
                    )
            ) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        return Registration.Ready(
            KeyCreation.Created(
                KeyMaterial(
                    authenticators = request.authenticators,
                    generated = generated,
                    authData = authData,
                    toSign = toSign,
                    signature = signature,
                    prfSecurityLevel = prfSecurityLevel,
                ),
            ),
        )
    }

    suspend fun persist(record: PasskeyRecord): Registration<Unit> =
        passkeyRepo.add(record).fold(
            onSuccess = {
                migrationRepo.delete(record.rp.id, record.user.name).onFailure { error ->
                    log.e(error) { "placeholder cleanup failed" }
                    Toast.makeText(context, ErrorMessages.PLACEHOLDER_CLEANUP_FAILED, Toast.LENGTH_LONG).show()
                }
                Registration.Ready(Unit)
            },
            onFailure = {
                log.e(it) { "saving passkey failed" }
                Registration.Interrupted(ErrorMessages.SAVE_FAILED)
            },
        )

    suspend fun macFor(alias: KeyAlias): Outcome<Mac> =
        try {
            Outcome.Success(withContext(dispatcher) { keyManager.macFor(alias) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }

    suspend fun hmacAuthenticatorsFor(alias: KeyAlias): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(withContext(dispatcher) { keyManager.allowedAuthenticatorsForHmac(alias) })
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }

    private suspend fun generateKey(request: SecureKeyManager.CredentialKeyRequest): Outcome<SecureKeyManager.CredentialKeyGeneration> =
        try {
            Outcome.Success(withContext(dispatcher) { keyManager.generateCredentialKey(request) })
        } catch (e: SecureElementUnavailableException) {
            Outcome.Failure(e.message)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to generate key" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        } catch (e: ProviderException) {
            log.e(e) { "failed to generate key" }
            Outcome.Failure(ErrorMessages.hardwareError(e))
        }

    private suspend fun generatePrfKey(
        credentialId: CredentialId,
        keystoreAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
        prfRequested: Boolean,
    ): Outcome<KeySecurityLevel?> {
        if (!prfRequested) return Outcome.Success(null)
        return try {
            Outcome.Success(
                withContext(dispatcher) {
                    keyManager
                        .generateHmacKey(
                            credentialId.hmacKeyAlias,
                            keystoreAuthenticators,
                            invalidateOnBiometricEnrollment,
                        ).securityLevel
                },
            )
        } catch (e: SecureElementUnavailableException) {
            Outcome.Failure(e.message)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to generate prf key" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        } catch (e: ProviderException) {
            log.e(e) { "failed to generate prf key" }
            Outcome.Failure(ErrorMessages.hardwareError(e))
        }
    }

    private suspend fun signFor(
        alias: KeyAlias,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<Signature> =
        try {
            Outcome.Success(withContext(dispatcher) { keyManager.signatureFor(alias, algorithm) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "failed to sign" }
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to sign" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }
}
