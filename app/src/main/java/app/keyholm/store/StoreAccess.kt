package app.keyholm.store

import co.touchlab.kermit.Logger
import java.io.IOException

private val log = Logger.withTag("app.keyholm.store.StoreAccess")

internal suspend fun <T> readStore(read: suspend () -> T): T? =
    try {
        read()
    } catch (e: IOException) {
        log.e(e) { "store read failed" }
        null
    } catch (e: StoredRecordException) {
        log.e(e) { "store read failed" }
        null
    }

internal suspend fun writeStore(write: suspend () -> Result<Unit>): Boolean =
    write()
        .onFailure {
            log.e(it) { "store write failed" }
        }.isSuccess
