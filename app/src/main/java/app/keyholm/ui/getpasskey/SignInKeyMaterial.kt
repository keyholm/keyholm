package app.keyholm.ui.getpasskey

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.util.logger
import app.keyholm.webauthn.KeyAlias
import app.keyholm.webauthn.WebAuthnAlgorithm
import java.security.GeneralSecurityException
import java.security.Signature
import javax.crypto.Mac

internal class SignInKeyMaterial(
    private val passkeyRepo: PasskeyRepository,
) {
    private val log = logger()

    suspend fun signFor(
        record: PasskeyRecord,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<Signature> =
        try {
            Outcome.Success(SecureKeyManager().signatureFor(record.keyAlias, algorithm))
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
            Outcome.Success(SecureKeyManager().macFor(record.hmacKeyAlias))
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            markLikelyInvalid(record)
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    fun hmacAuthenticatorsFor(alias: KeyAlias): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(SecureKeyManager().allowedAuthenticatorsForHmac(alias))
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_GET)
        }

    fun authenticatorsFor(
        alias: KeyAlias,
        algorithm: WebAuthnAlgorithm,
    ): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(SecureKeyManager().allowedAuthenticatorsFor(alias, algorithm))
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
