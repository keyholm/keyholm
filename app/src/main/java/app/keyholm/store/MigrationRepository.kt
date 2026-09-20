package app.keyholm.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import app.keyholm.store.proto.MigrationPlaceholdersProto
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val MIGRATION_STORE_FILE = "migration_placeholders.pb"

private val Context.migrationDataStore: DataStore<MigrationPlaceholdersProto> by dataStore(
    fileName = MIGRATION_STORE_FILE,
    serializer = MigrationPlaceholdersSerializer,
)

internal fun deleteMigrationStoreFile(context: Context): Boolean {
    val file = context.dataStoreFile(MIGRATION_STORE_FILE)
    return !file.exists() || file.delete()
}

class MigrationRepository internal constructor(
    private val dataStore: DataStore<MigrationPlaceholdersProto>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(context: Context) : this(context.applicationContext.migrationDataStore)

    val placeholders: Flow<List<MigrationPlaceholder>> =
        dataStore.data
            .map { proto -> proto.placeholdersList.map { it.toDomain() } }
            .flowOn(dispatcher)

    suspend fun addAll(records: List<MigrationPlaceholder>): Result<Unit> {
        if (records.isEmpty()) return Result.success(Unit)
        return write { it.toBuilder().addAllPlaceholders(records.map { r -> r.toProto() }).build() }
    }

    suspend fun delete(
        rpId: RpId,
        userName: String,
    ): Result<Unit> =
        write { current ->
            val kept = current.placeholdersList.filterNot { it.rpId == rpId.value && it.userName == userName }
            MigrationPlaceholdersProto.newBuilder().addAllPlaceholders(kept).build()
        }

    suspend fun saveAll(records: List<MigrationPlaceholder>): Result<Unit> =
        write {
            MigrationPlaceholdersProto.newBuilder().addAllPlaceholders(records.map { placeholder -> placeholder.toProto() }).build()
        }

    private suspend fun write(transform: (MigrationPlaceholdersProto) -> MigrationPlaceholdersProto): Result<Unit> =
        try {
            dataStore.updateData(transform)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
}
