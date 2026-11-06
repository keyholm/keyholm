package app.keyholm.ui.getpasskey

import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.KeyAccess
import app.keyholm.authenticator.KeyFailure
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.ui.common.toastMessage
import app.keyholm.util.logger
import kotlinx.coroutines.CoroutineDispatcher
import java.security.Signature

private fun <T> KeyAccess<T>.toOutcome(): Outcome<T> =
    when (this) {
        is KeyAccess.Success -> Outcome.Success(value)
        is KeyAccess.Failure -> Outcome.Failure(failure.toastMessage(ErrorMessages.SECURITY_ERROR_GET))
    }

internal class SignInKeyMaterial(
    private val passkeyRepo: PasskeyRepository,
    dispatcher: CoroutineDispatcher,
) {
    val keys = AssertionKeyMaterial(passkeyRepo, dispatcher)
    private val log = logger()

    suspend fun signFor(record: PasskeyRecord): Outcome<Signature> = keys.signatureFor(record).toOutcome()

    suspend fun setUpPrf(record: PasskeyRecord): Outcome<PasskeyRecord> {
        val securityLevel =
            when (val generated = keys.generatePrfKey(record)) {
                is KeyAccess.Failure -> {
                    val message =
                        when (val failure = generated.failure) {
                            is KeyFailure.SecureElementUnavailable -> failure.message
                            else -> ErrorMessages.PRF_SETUP_FAILED
                        }
                    return Outcome.Failure(message)
                }

                is KeyAccess.Success -> {
                    generated.value
                }
            }
        val updated = record.copy(keystore = record.keystore.copy(prfSecurityLevel = securityLevel))
        return when (updateRecord(updated)) {
            is Outcome.Failure -> Outcome.Failure(ErrorMessages.PRF_SETUP_FAILED)
            is Outcome.Success -> Outcome.Success(updated)
        }
    }

    suspend fun authenticatorsFor(record: PasskeyRecord): Outcome<AuthenticatorPolicy> = keys.authenticatorsFor(record).toOutcome()

    suspend fun updateRecord(record: PasskeyRecord): Outcome<Unit> =
        passkeyRepo.update(record).fold(
            onSuccess = { Outcome.Success(Unit) },
            onFailure = {
                log.e(it) { "updateRecord failed" }
                Outcome.Failure(ErrorMessages.UPDATE_FAILED)
            },
        )
}
