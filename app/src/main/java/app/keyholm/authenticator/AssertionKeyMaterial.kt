package app.keyholm.authenticator

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.SecureElementUnavailableException
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.util.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature
import javax.crypto.Mac

class AssertionKeyMaterial(
    private val passkeyRepo: PasskeyRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    private val keyManager by lazy { SecureKeyManager() }
    private val hmacKeyManager by lazy { HmacKeyManager() }
    private val log = logger()

    suspend fun signatureFor(record: PasskeyRecord): KeyAccess<Signature> =
        try {
            KeyAccess.Success(withContext(dispatcher) { keyManager.signatureFor(record.credentialId, record.keystore.coseAlgorithm) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "signatureFor failed" }
            markLikelyInvalid(record)
            KeyAccess.Failure(KeyFailure.KeyInvalidated)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "signatureFor failed" }
            KeyAccess.Failure(KeyFailure.SecurityError)
        }

    suspend fun macFor(record: PasskeyRecord): KeyAccess<Mac> =
        try {
            KeyAccess.Success(withContext(dispatcher) { hmacKeyManager.macFor(record.credentialId) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            markLikelyInvalid(record)
            KeyAccess.Failure(KeyFailure.KeyInvalidated)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            KeyAccess.Failure(KeyFailure.SecurityError)
        }

    suspend fun authenticatorsFor(record: PasskeyRecord): KeyAccess<AuthenticatorPolicy> =
        try {
            KeyAccess.Success(
                withContext(dispatcher) { keyManager.allowedAuthenticatorsFor(record.credentialId, record.keystore.coseAlgorithm) },
            )
        } catch (e: GeneralSecurityException) {
            log.e(e) { "authenticatorsFor failed" }
            KeyAccess.Failure(KeyFailure.SecurityError)
        }

    suspend fun hmacAuthenticatorsFor(record: PasskeyRecord): KeyAccess<AuthenticatorPolicy> =
        try {
            KeyAccess.Success(withContext(dispatcher) { hmacKeyManager.allowedAuthenticatorsForHmac(record.credentialId) })
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            KeyAccess.Failure(KeyFailure.SecurityError)
        }

    suspend fun generatePrfKey(record: PasskeyRecord): KeyAccess<KeySecurityLevel> =
        try {
            KeyAccess.Success(
                withContext(dispatcher) {
                    val credentialKey = keyManager.credentialKeyInfo(record.credentialId, record.keystore.coseAlgorithm)
                    hmacKeyManager.generateHmacKeyFor(credentialKey, record.credentialId).securityLevel
                },
            )
        } catch (e: SecureElementUnavailableException) {
            log.e(e) { "failed to generate prf key" }
            KeyAccess.Failure(KeyFailure.SecureElementUnavailable(e.message))
        } catch (e: GeneralSecurityException) {
            log.e(e) { "failed to generate prf key" }
            KeyAccess.Failure(KeyFailure.SecurityError)
        } catch (e: ProviderException) {
            log.e(e) { "failed to generate prf key" }
            KeyAccess.Failure(KeyFailure.HardwareError(e))
        }

    private suspend fun markLikelyInvalid(record: PasskeyRecord) {
        if (record.likelyInvalid) return
        passkeyRepo.update(record.copy(likelyInvalid = true)).onFailure {
            log.e(it) { "marking the passkey likely-invalid failed" }
        }
    }
}
