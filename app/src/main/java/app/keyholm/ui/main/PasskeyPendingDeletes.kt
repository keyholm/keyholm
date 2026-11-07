package app.keyholm.ui.main

import androidx.biometric.BiometricPrompt
import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.KeyAccess
import app.keyholm.authenticator.KeyFailure
import app.keyholm.ctap.CredentialId
import app.keyholm.keystore.PasskeyKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.ErrorMessages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

class PasskeyPendingDeletes(
    private val repo: PasskeyRepository,
    dispatcher: CoroutineDispatcher,
) : PendingDeleteStore<CredentialId, PasskeyRecord> {
    private val keyManager = PasskeyKeyManager()
    private val keys = AssertionKeyMaterial(repo, dispatcher)

    override val records: Flow<List<PasskeyRecord>> = repo.passkeys

    override fun id(record: PasskeyRecord) = record.credentialId

    override fun lifecycle(record: PasskeyRecord) = record.lifecycle

    override fun withLifecycle(
        record: PasskeyRecord,
        lifecycle: RecordLifecycle,
    ) = record.copy(lifecycle = lifecycle)

    override suspend fun update(record: PasskeyRecord) = repo.update(record)

    override suspend fun delete(id: CredentialId) = repo.delete(id)

    override suspend fun planDelete(record: PasskeyRecord): DeletePlan {
        val allowedAuthenticators =
            when (val read = keys.authenticatorsFor(record)) {
                is KeyAccess.Success -> {
                    read.value
                }

                is KeyAccess.Failure -> {
                    return when (read.failure) {
                        KeyFailure.KeyMissing -> DeletePlan.Orphaned
                        else -> DeletePlan.Failed(ErrorMessages.DELETE_KEY_UNAVAILABLE)
                    }
                }
            }
        val cryptoObject =
            when (val signature = keys.signatureFor(record)) {
                is KeyAccess.Success -> BiometricPrompt.CryptoObject(signature.value)
                is KeyAccess.Failure -> null
            }
        return DeletePlan.Confirm(cryptoObject, allowedAuthenticators)
    }

    override fun deleteKeyMaterial(record: PasskeyRecord) = keyManager.deleteKeys(record.credentialId)
}
