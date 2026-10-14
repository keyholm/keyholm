package app.keyholm.store.encrypted

import androidx.datastore.core.DataStore
import app.keyholm.store.encrypted.exceptions.StoreLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeferredDataStore<T>(
    private val dispatcher: CoroutineDispatcher,
    private val create: suspend (CoroutineScope) -> DataStore<T>,
) : DataStore<T> {
    private class Open<T>(
        val store: DataStore<T>,
        val scope: CoroutineScope,
    )

    private val mutex = Mutex()
    private var open: Open<T>? = null

    private suspend fun store(): DataStore<T> =
        mutex.withLock {
            open?.store ?: run {
                val scope = CoroutineScope(dispatcher + SupervisorJob())
                create(scope).also { open = Open(it, scope) }
            }
        }

    override val data: Flow<T> =
        flow {
            emitAll(store().data.catch { if (it is CancellationException) throw StoreLockedException(it) else throw it })
            throw StoreLockedException(IllegalStateException("the store was closed while being read"))
        }

    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        try {
            store().updateData(transform)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw StoreLockedException(e)
        }

    suspend fun close() {
        mutex.withLock {
            open
                ?.scope
                ?.coroutineContext
                ?.job
                ?.cancelAndJoin()
            open = null
        }
    }
}
