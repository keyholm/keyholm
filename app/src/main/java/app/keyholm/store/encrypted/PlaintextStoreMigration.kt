package app.keyholm.store.encrypted

import androidx.datastore.core.DataMigration
import androidx.datastore.core.Serializer
import app.keyholm.util.logger
import java.io.File

internal class PlaintextStoreMigration<T>(
    private val oldFile: File,
    private val newFile: File,
    private val serializer: Serializer<T>,
) : DataMigration<T> {
    private val log = logger()

    override suspend fun shouldMigrate(currentData: T): Boolean {
        if (!oldFile.exists()) return false
        if (newFile.exists()) {
            deleteOldFile()
            return false
        }
        return true
    }

    override suspend fun migrate(currentData: T): T = oldFile.inputStream().use { serializer.readFrom(it) }

    override suspend fun cleanUp() = deleteOldFile()

    private fun deleteOldFile() {
        if (!oldFile.delete()) log.e { "couldn't delete the plaintext store" }
    }
}
