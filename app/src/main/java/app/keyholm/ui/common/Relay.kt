package app.keyholm.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PendingRequest<Request, Response>(
    val request: Request,
) {
    val response = CompletableDeferred<Response>()
}

class Relay<Request, Response> {
    private val _pending = MutableStateFlow<PendingRequest<Request, Response>?>(null)
    val pending: StateFlow<PendingRequest<Request, Response>?> = _pending.asStateFlow()

    suspend fun ask(request: Request): Response {
        val pending = PendingRequest<Request, Response>(request)
        _pending.value = pending
        return try {
            pending.response.await()
        } finally {
            _pending.compareAndSet(pending, null)
        }
    }
}
