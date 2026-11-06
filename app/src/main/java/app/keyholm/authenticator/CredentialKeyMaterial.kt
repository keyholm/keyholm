package app.keyholm.authenticator

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.keyholm.ctap.CredentialId
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.SecureElementUnavailableException
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.keystore.deleteCredentialKeys
import app.keyholm.util.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature

class CredentialKeys(
    val generated: SecureKeyManager.GeneratedCredential,
    val signature: Signature,
    val prfSecurityLevel: KeySecurityLevel?,
)

sealed interface CredentialKeyCreation {
    data class Created(
        val keys: CredentialKeys,
    ) : CredentialKeyCreation

    data object DevicePropertiesUnavailable : CredentialKeyCreation

    data class Failed(
        val failure: KeyFailure,
    ) : CredentialKeyCreation
}

class CredentialKeyMaterial(
    private val dispatcher: CoroutineDispatcher,
) {
    private val keyManager by lazy { SecureKeyManager() }
    private val hmacKeyManager by lazy { HmacKeyManager() }
    private val log = logger()

    suspend fun create(
        request: SecureKeyManager.CredentialKeyRequest,
        prfRequested: Boolean,
    ): CredentialKeyCreation {
        val generation =
            try {
                withContext(dispatcher) { keyManager.generateCredentialKey(request) }
            } catch (e: SecureElementUnavailableException) {
                return CredentialKeyCreation.Failed(KeyFailure.SecureElementUnavailable(e.message))
            } catch (e: GeneralSecurityException) {
                log.e(e) { "failed to generate key" }
                return CredentialKeyCreation.Failed(KeyFailure.SecurityError)
            } catch (e: ProviderException) {
                log.e(e) { "failed to generate key" }
                return CredentialKeyCreation.Failed(KeyFailure.HardwareError(e))
            }
        return when (generation) {
            is SecureKeyManager.CredentialKeyGeneration.Generated -> {
                var keys: CredentialKeyCreation? = null
                try {
                    keys = keysFor(request, generation.credential, prfRequested)
                } finally {
                    if (keys !is CredentialKeyCreation.Created) withContext(NonCancellable) { discard(request.credentialId) }
                }
                keys
            }

            SecureKeyManager.CredentialKeyGeneration.DevicePropertiesUnavailable -> {
                CredentialKeyCreation.DevicePropertiesUnavailable
            }
        }
    }

    suspend fun discard(credentialId: CredentialId) =
        withContext(dispatcher) { deleteCredentialKeys(credentialId, keyManager, hmacKeyManager) }

    private suspend fun keysFor(
        request: SecureKeyManager.CredentialKeyRequest,
        generated: SecureKeyManager.GeneratedCredential,
        prfRequested: Boolean,
    ): CredentialKeyCreation {
        val signature =
            try {
                withContext(dispatcher) { keyManager.signatureFor(request.credentialId, request.algorithm) }
            } catch (e: KeyPermanentlyInvalidatedException) {
                log.e(e) { "failed to sign" }
                return CredentialKeyCreation.Failed(KeyFailure.KeyInvalidated)
            } catch (e: GeneralSecurityException) {
                log.e(e) { "failed to sign" }
                return CredentialKeyCreation.Failed(KeyFailure.SecurityError)
            }
        val prfSecurityLevel =
            if (prfRequested) {
                try {
                    withContext(dispatcher) {
                        hmacKeyManager
                            .generateHmacKey(request.credentialId, request.authenticators, request.invalidateOnBiometricEnrollment)
                            .securityLevel
                    }
                } catch (e: SecureElementUnavailableException) {
                    return CredentialKeyCreation.Failed(KeyFailure.SecureElementUnavailable(e.message))
                } catch (e: GeneralSecurityException) {
                    log.e(e) { "failed to generate prf key" }
                    return CredentialKeyCreation.Failed(KeyFailure.SecurityError)
                } catch (e: ProviderException) {
                    log.e(e) { "failed to generate prf key" }
                    return CredentialKeyCreation.Failed(KeyFailure.HardwareError(e))
                }
            } else {
                null
            }
        return CredentialKeyCreation.Created(CredentialKeys(generated, signature, prfSecurityLevel))
    }
}
