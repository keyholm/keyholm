package app.keyholm.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import app.keyholm.store.proto.PasskeyRecordsProto
import app.keyholm.webauthn.CredentialId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

data class PasskeySummary(
    val count: Int,
    val lastUsedTime: Instant?,
)

internal fun summarize(records: List<PasskeyRecord>): PasskeySummary =
    PasskeySummary(
        count = records.size,
        lastUsedTime = records.maxOfOrNull { it.lastUsedAt },
    )

private const val PASSKEY_STORE_FILE = "passkeys.pb"

private val Context.passkeyDataStore: DataStore<PasskeyRecordsProto> by dataStore(
    fileName = PASSKEY_STORE_FILE,
    serializer = PasskeyRecordsSerializer,
)

internal fun deletePasskeyStoreFile(context: Context): Boolean {
    val file = context.dataStoreFile(PASSKEY_STORE_FILE)
    return !file.exists() || file.delete()
}

class PasskeyRepository internal constructor(
    private val dataStore: DataStore<PasskeyRecordsProto>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(context: Context) : this(context.applicationContext.passkeyDataStore)

    val passkeys: Flow<List<PasskeyRecord>> =
        dataStore.data
            .map { proto -> proto.recordsList.map { it.toDomain() } }
            .flowOn(dispatcher)

    suspend fun summary(): PasskeySummary = summarize(passkeys.first())

    suspend fun add(record: PasskeyRecord): Result<Unit> = write { it.toBuilder().addRecords(record.toProto()).build() }

    suspend fun update(record: PasskeyRecord): Result<Unit> =
        edit(record.credentialId) { current, idx ->
            current.toBuilder().setRecords(idx, record.toProto()).build()
        }

    suspend fun delete(credentialId: CredentialId): Result<Unit> =
        edit(credentialId) { current, idx -> current.toBuilder().removeRecords(idx).build() }

    private suspend fun edit(
        credentialId: CredentialId,
        apply: (PasskeyRecordsProto, Int) -> PasskeyRecordsProto,
    ): Result<Unit> {
        var found = false
        return write { current ->
            val idx = current.recordsList.indexOfFirst { it.credentialId == credentialId.b64 }
            found = idx >= 0
            if (idx < 0) current else apply(current, idx)
        }.mapCatching {
            if (!found) throw NoSuchElementException("no stored passkey ${credentialId.b64}")
        }
    }

    suspend fun saveAll(records: List<PasskeyRecord>): Result<Unit> =
        write {
            PasskeyRecordsProto.newBuilder().addAllRecords(records.map { r -> r.toProto() }).build()
        }

    private suspend fun write(transform: (PasskeyRecordsProto) -> PasskeyRecordsProto): Result<Unit> =
        try {
            dataStore.updateData(transform)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
}
