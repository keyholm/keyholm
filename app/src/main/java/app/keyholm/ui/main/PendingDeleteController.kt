package app.keyholm.ui.main

import androidx.biometric.BiometricPrompt
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.store.RecordLifecycle
import app.keyholm.store.readStore
import app.keyholm.store.writeStore
import app.keyholm.ui.common.ErrorMessages
import kotlinx.coroutines.CoroutineDispatcher
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

sealed interface DeletePlan {
    data class Confirm(
        val cryptoObject: BiometricPrompt.CryptoObject?,
        val allowedAuthenticators: AuthenticatorPolicy,
    ) : DeletePlan

    data object Orphaned : DeletePlan

    data class Failed(
        val message: String,
    ) : DeletePlan
}

interface PendingDeleteStore<Id, R> {
    val records: Flow<List<R>>

    fun id(record: R): Id

    fun lifecycle(record: R): RecordLifecycle

    fun withLifecycle(
        record: R,
        lifecycle: RecordLifecycle,
    ): R

    suspend fun update(record: R): Result<Unit>

    suspend fun delete(id: Id): Result<Unit>

    fun planDelete(record: R): DeletePlan

    fun deleteKeyMaterial(record: R)
}

private class Pending<R>(
    val record: R,
    val job: Job,
)

private typealias PendingDeletes<Id, R> = MutableStateFlow<Map<Id, Pending<R>>>

private fun <Id, R> PendingDeletes<Id, R>.cancel(id: Id) {
    getAndUpdate { it - id }[id]?.job?.cancel()
}

private fun <Id, R> PendingDeletes<Id, R>.cancelAll(): List<Pending<R>> =
    getAndUpdate { emptyMap() }.values.onEach { it.job.cancel() }.toList()

class PendingDeleteController<Id, R>(
    private val store: PendingDeleteStore<Id, R>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onError: (String) -> Unit,
) {
    private val pending: PendingDeletes<Id, R> = MutableStateFlow(emptyMap())

    val batch: Flow<List<R>> = pending.map { it.values.map(Pending<R>::record) }

    fun resume() {
        scope.launch {
            val now = Instant.now()
            val stored = readStore { store.records.first() } ?: return@launch
            val pendingFromStore =
                stored.mapNotNull { record ->
                    (store.lifecycle(record) as? RecordLifecycle.PendingDelete)?.let { record to it.at }
                }
            val (overdue, inFlight) = pendingFromStore.partition { (_, at) -> !at.isAfter(now) }
            overdue.forEach { (record, _) -> deleteKeyMaterial(record) }
            overdue.forEach { (record, _) -> forget(store.id(record)) }
            inFlight.forEach { (record, at) -> arm(record, Duration.between(now, at).toMillis()) }
        }
    }

    fun start(record: R) {
        val deleteAt = RecordLifecycle.PendingDelete(Instant.now().plusMillis(UNDO_WINDOW_MS))
        val newPending = store.withLifecycle(record, deleteAt)
        scope.launch { write { store.update(newPending) } }
        arm(newPending, UNDO_WINDOW_MS)
    }

    private fun arm(
        record: R,
        remainingMs: Long,
    ) {
        val id = store.id(record)
        if (id in pending.value) return
        val job =
            scope.launch {
                delay(remainingMs)
                deleteKeyMaterial(record)
                forget(id)
                pending.update { it - id }
            }
        pending.update { it + (id to Pending(record, job)) }
    }

    fun cancel(record: R) {
        pending.cancel(store.id(record))
        scope.launch { restore(record) }
    }

    fun undoAll() {
        val undone = pending.cancelAll()
        scope.launch { undone.forEach { restore(it.record) } }
    }

    fun clear() {
        pending.cancelAll()
    }

    suspend fun planFor(record: R): DeletePlan = withContext(dispatcher) { store.planDelete(record) }

    private suspend fun deleteKeyMaterial(record: R) {
        withContext(dispatcher) { store.deleteKeyMaterial(record) }
    }

    private suspend fun restore(record: R) = write { store.update(store.withLifecycle(record, RecordLifecycle.Active)) }

    private suspend fun forget(id: Id) = write { store.delete(id) }

    private suspend fun write(block: suspend () -> Result<Unit>) {
        if (!writeStore(block)) onError(ErrorMessages.UPDATE_FAILED)
    }
}
