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
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.WebAuthnAlgorithm
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature
import javax.crypto.Mac

internal class RegistrationKeyMaterial(
    private val context: Context,
    private val passkeyRepo: PasskeyRepository,
    private val migrationRepo: MigrationRepository,
) {
    private val keyManager = SecureKeyManager()
    private val log = logger()

    fun deleteKey(alias: KeyAlias) = keyManager.deleteKey(alias)

    fun resolveAuthenticators(policy: AuthenticatorPolicy): Registration<AuthenticatorPolicy> =
        when (val r = resolveCreateAuthenticators(context, policy)) {
            is AuthenticatorsResolution.Ready -> Registration.Ready(r.value)
            is AuthenticatorsResolution.Unavailable -> Registration.NoCreateOption(r.message)
        }

    fun create(
        alias: KeyAlias,
        clientDataHash: ClientDataHash,
        rpId: RpId,
        credentialId: CredentialId,
        algorithm: WebAuthnAlgorithm,
        prfRequested: Boolean,
        identifyAsKeyholm: Boolean,
        createAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): Registration<KeyMaterial> {
        val authenticators =
            when (val r = resolveAuthenticators(createAuthenticators)) {
                is Registration.Failed -> return r
                is Registration.Ready -> r.value
            }
        val generated =
            when (
                val r = generateKey(alias, clientDataHash, algorithm, authenticators, invalidateOnBiometricEnrollment)
            ) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        val aaguid = if (identifyAsKeyholm) WebAuthn.KEYHOLM_AAGUID else WebAuthn.ZERO_AAGUID
        val authData =
            WebAuthn.registrationAuthData(rpId, credentialId, generated.publicKey, aaguid, algorithm)
        val toSign = SigningInput(authData.bytes + clientDataHash.bytes)
        val signature =
            when (val r = signFor(alias, algorithm)) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        val prfSecurityLevel =
            when (
                val r = generatePrfKey(credentialId, authenticators, invalidateOnBiometricEnrollment, prfRequested)
            ) {
                is Outcome.Failure -> return Registration.Internal(r.toastMessage)
                is Outcome.Success -> r.value
            }
        return Registration.Ready(
            KeyMaterial(
                authenticators = authenticators,
                generated = generated,
                authData = authData,
                toSign = toSign,
                signature = signature,
                prfSecurityLevel = prfSecurityLevel,
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

    fun macFor(alias: KeyAlias): Outcome<Mac> =
        try {
            Outcome.Success(keyManager.macFor(alias))
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }

    fun hmacAuthenticatorsFor(alias: KeyAlias): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(keyManager.allowedAuthenticatorsForHmac(alias))
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }

    private fun generateKey(
        alias: KeyAlias,
        clientDataHash: ClientDataHash,
        algorithm: WebAuthnAlgorithm,
        keystoreAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): Outcome<SecureKeyManager.GeneratedCredential> =
        try {
            Outcome.Success(
                keyManager.generateCredentialKey(
                    alias,
                    clientDataHash,
                    algorithm,
                    keystoreAuthenticators,
                    invalidateOnBiometricEnrollment,
                ),
            )
        } catch (e: SecureElementUnavailableException) {
            Outcome.Failure(e.message)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to generate key" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        } catch (e: ProviderException) {
            log.e(e) { "failed to generate key" }
            Outcome.Failure(ErrorMessages.hardwareError(e))
        }

    private fun generatePrfKey(
        credentialId: CredentialId,
        keystoreAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
        prfRequested: Boolean,
    ): Outcome<KeySecurityLevel?> {
        if (!prfRequested) return Outcome.Success(null)
        return try {
            Outcome.Success(
                keyManager
                    .generateHmacKey(
                        credentialId.hmacKeyAlias,
                        keystoreAuthenticators,
                        invalidateOnBiometricEnrollment,
                    ).securityLevel,
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

    private fun signFor(
        alias: KeyAlias,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<Signature> =
        try {
            Outcome.Success(keyManager.signatureFor(alias, algorithm))
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "failed to sign" }
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to sign" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }
}
