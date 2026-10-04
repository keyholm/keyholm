package app.keyholm.ui.getpasskey

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.SecureElementUnavailableException
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.WebAuthnAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.Signature
import javax.crypto.Mac

internal class SignInKeyMaterial(
    private val passkeyRepo: PasskeyRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()

    suspend fun signFor(
        record: PasskeyRecord,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<Signature> =
        try {
            Outcome.Success(withContext(dispatcher) { SecureKeyManager().signatureFor(record.credentialId, algorithm) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "signatureFor failed" }
            markLikelyInvalid(record)
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "signatureFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    suspend fun macFor(record: PasskeyRecord): Outcome<Mac> =
        try {
            Outcome.Success(withContext(dispatcher) { HmacKeyManager().macFor(record.credentialId) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            markLikelyInvalid(record)
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    suspend fun setUpPrf(record: PasskeyRecord): Outcome<PasskeyRecord> {
        val generated =
            try {
                withContext(dispatcher) {
                    val credentialKey = SecureKeyManager().credentialKeyInfo(record.credentialId, record.keystore.coseAlgorithm)
                    HmacKeyManager().generateHmacKeyFor(credentialKey, record.credentialId)
                }
            } catch (e: SecureElementUnavailableException) {
                log.e(e) { "failed to generate prf key" }
                return Outcome.Failure(e.message)
            } catch (e: GeneralSecurityException) {
                log.e(e) { "failed to generate prf key" }
                return Outcome.Failure(ErrorMessages.PRF_SETUP_FAILED)
            } catch (e: ProviderException) {
                log.e(e) { "failed to generate prf key" }
                return Outcome.Failure(ErrorMessages.PRF_SETUP_FAILED)
            }
        val updated = record.copy(keystore = record.keystore.copy(prfSecurityLevel = generated.securityLevel))
        return when (updateRecord(updated)) {
            is Outcome.Failure -> Outcome.Failure(ErrorMessages.PRF_SETUP_FAILED)
            is Outcome.Success -> Outcome.Success(updated)
        }
    }

    suspend fun hmacAuthenticatorsFor(credentialId: CredentialId): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(withContext(dispatcher) { HmacKeyManager().allowedAuthenticatorsForHmac(credentialId) })
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    suspend fun authenticatorsFor(
        credentialId: CredentialId,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(withContext(dispatcher) { SecureKeyManager().allowedAuthenticatorsFor(credentialId, algorithm) })
        } catch (e: GeneralSecurityException) {
            log.e(e) { "authenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    suspend fun updateRecord(record: PasskeyRecord): Outcome<Unit> =
        passkeyRepo.update(record).fold(
            onSuccess = { Outcome.Success(Unit) },
            onFailure = {
                log.e(it) { "updateRecord failed" }
                Outcome.Failure(ErrorMessages.UPDATE_FAILED)
            },
        )

    private suspend fun markLikelyInvalid(record: PasskeyRecord) {
        if (record.likelyInvalid) return
        updateRecord(record.copy(likelyInvalid = true))
    }
}
