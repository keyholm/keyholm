package app.keyholm.ui.main

import androidx.biometric.BiometricPrompt
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
import kotlinx.coroutines.flow.Flow
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

class PasskeyPendingDeletes(
    private val repo: PasskeyRepository,
) : PendingDeleteStore<CredentialId, PasskeyRecord> {
    private val log = logger()

    override val records: Flow<List<PasskeyRecord>> = repo.passkeys

    override fun id(record: PasskeyRecord) = record.credentialId

    override fun lifecycle(record: PasskeyRecord) = record.lifecycle

    override fun withLifecycle(
        record: PasskeyRecord,
        lifecycle: RecordLifecycle,
    ) = record.copy(lifecycle = lifecycle)

    override suspend fun update(record: PasskeyRecord) = repo.update(record)

    override suspend fun delete(id: CredentialId) = repo.delete(id)

    override fun planDelete(record: PasskeyRecord): DeletePlan {
        val algorithm = record.keystore.coseAlgorithm
        val keyManager = SecureKeyManager()
        val allowedAuthenticators =
            try {
                keyManager.allowedAuthenticatorsFor(record.keyAlias, algorithm)
            } catch (e: UnrecoverableKeyException) {
                log.e(e) { "no key material for ${record.keyAlias.value}, treating as orphaned" }
                return DeletePlan.Orphaned
            } catch (e: GeneralSecurityException) {
                log.e(e) { "couldn't read authenticators for ${record.keyAlias.value}" }
                return DeletePlan.Failed(ErrorMessages.DELETE_KEY_UNAVAILABLE)
            } catch (e: ProviderException) {
                log.e(e) { "couldn't read authenticators for ${record.keyAlias.value}" }
                return DeletePlan.Failed(ErrorMessages.DELETE_KEY_UNAVAILABLE)
            }
        val cryptoObject =
            try {
                BiometricPrompt.CryptoObject(keyManager.signatureFor(record.keyAlias, algorithm))
            } catch (e: GeneralSecurityException) {
                log.e(e) { "couldn't create a signature for ${record.keyAlias.value}" }
                null
            }
        return DeletePlan.Confirm(cryptoObject, allowedAuthenticators)
    }

    override fun deleteKeyMaterial(record: PasskeyRecord) {
        SecureKeyManager().deleteKey(record.keyAlias)
        if (record.hasPrf) SecureKeyManager().deleteKey(record.hmacKeyAlias)
    }
}
