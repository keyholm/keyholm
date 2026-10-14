package app.keyholm.store

import co.touchlab.kermit.Logger
import java.io.IOException

private val log = Logger.withTag("app.keyholm.store.StoreAccess")

private fun logFailure(
    e: Throwable,
    operation: String,
) {
    if (e.isStoreLocked()) log.w(e) { "store $operation failed, device locked" } else log.e(e) { "store $operation failed" }
}

internal suspend fun <T> readStore(read: suspend () -> T): T? =
    try {
        read()
    } catch (e: IOException) {
        logFailure(e, "read")
        null
    } catch (e: StoredRecordException) {
        logFailure(e, "read")
        null
    }

internal suspend fun writeStore(write: suspend () -> Result<Unit>): Boolean =
    write()
        .onFailure { logFailure(it, "write") }
        .isSuccess
