package app.keyholm.ui.main

import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.RecordLifecycle
import app.keyholm.store.readStore
import app.keyholm.store.writeStore
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.webauthn.CredentialId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

internal const val UNDO_WINDOW_MS = 3_000L

private class Pending(
    val record: PasskeyRecord,
    val job: Job,
)

private typealias PendingDeletes = MutableStateFlow<Map<CredentialId, Pending>>

private fun PendingDeletes.cancel(credentialId: CredentialId) {
    getAndUpdate { it - credentialId }[credentialId]?.job?.cancel()
}

private fun PendingDeletes.cancelAll(): List<Pending> = getAndUpdate { emptyMap() }.values.onEach { it.job.cancel() }.toList()

class PendingDeleteController(
    private val passkeyRepo: PasskeyRepository,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val pending: PendingDeletes = MutableStateFlow(emptyMap())

    val batch: Flow<List<PasskeyRecord>> = pending.map { it.values.map(Pending::record) }

    fun resume() {
        scope.launch {
            val now = Instant.now()
            val stored = readStore { passkeyRepo.passkeys.first() } ?: return@launch
            val pendingFromStore =
                stored.mapNotNull { record ->
                    (record.lifecycle as? RecordLifecycle.PendingDelete)?.let { record to it.at }
                }
            val (overdue, inFlight) = pendingFromStore.partition { (_, at) -> !at.isAfter(now) }
            withContext(Dispatchers.IO) { overdue.forEach { (record, _) -> deleteKeyMaterial(record) } }
            overdue.forEach { (record, _) -> forget(record.credentialId) }
            inFlight.forEach { (record, at) -> arm(record, Duration.between(now, at).toMillis()) }
        }
    }

    fun start(record: PasskeyRecord) {
        val deleteAt = RecordLifecycle.PendingDelete(Instant.now().plusMillis(UNDO_WINDOW_MS))
        val newPending = record.copy(lifecycle = deleteAt)
        scope.launch { write { passkeyRepo.update(newPending) } }
        arm(newPending, UNDO_WINDOW_MS)
    }

    private fun arm(
        record: PasskeyRecord,
        remainingMs: Long,
    ) {
        if (record.credentialId in pending.value) return
        val job =
            scope.launch {
                delay(remainingMs)
                withContext(Dispatchers.IO) { deleteKeyMaterial(record) }
                forget(record.credentialId)
                pending.update { it - record.credentialId }
            }
        pending.update { it + (record.credentialId to Pending(record, job)) }
    }

    fun cancel(record: PasskeyRecord) {
        pending.cancel(record.credentialId)
        scope.launch { restore(record) }
    }

    fun undoAll() {
        val undone = pending.cancelAll()
        scope.launch { undone.forEach { restore(it.record) } }
    }

    fun clear() {
        pending.cancelAll()
    }

    private fun deleteKeyMaterial(record: PasskeyRecord) {
        SecureKeyManager().deleteKey(record.keyAlias)
        if (record.hasPrf) SecureKeyManager().deleteKey(record.hmacKeyAlias)
    }

    private suspend fun restore(record: PasskeyRecord) = write { passkeyRepo.update(record.copy(lifecycle = RecordLifecycle.Active)) }

    private suspend fun forget(credentialId: CredentialId) = write { passkeyRepo.delete(credentialId) }

    private suspend fun write(block: suspend () -> Result<Unit>) {
        if (!writeStore(block)) onError(ErrorMessages.UPDATE_FAILED)
    }
}
