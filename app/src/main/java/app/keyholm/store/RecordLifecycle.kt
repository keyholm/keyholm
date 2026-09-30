package app.keyholm.store

import java.time.Instant

sealed interface RecordLifecycle {
    data object Active : RecordLifecycle

    data class PendingDelete(
        val at: Instant,
    ) : RecordLifecycle
}
