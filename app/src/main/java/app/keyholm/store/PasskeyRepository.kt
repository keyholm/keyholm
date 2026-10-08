package app.keyholm.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.proto.DiscoverabilityProto
import app.keyholm.store.proto.PasskeyRecordProto
import app.keyholm.store.proto.PasskeyRecordsProto
import app.keyholm.webauthn.CredentialId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

internal fun deletePasskeyKeys(
    record: PasskeyRecord,
    keyManager: SecureKeyManager,
    hmacKeyManager: HmacKeyManager,
) {
    keyManager.deleteKey(record.credentialId)
    if (record.hasPrf) hmacKeyManager.deleteKey(record.credentialId)
}

class PasskeyRepository internal constructor(
    private val dataStore: DataStore<PasskeyRecordsProto>,
    private val deleteKeyMaterial: (PasskeyRecord) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(context: Context) : this(context, SecureKeyManager(), HmacKeyManager())

    private constructor(
        context: Context,
        keyManager: SecureKeyManager,
        hmacKeyManager: HmacKeyManager,
    ) : this(context.applicationContext.passkeyDataStore, { deletePasskeyKeys(it, keyManager, hmacKeyManager) })

    val passkeys: Flow<List<PasskeyRecord>> =
        dataStore.data
            .map { proto -> proto.recordsList.map { it.toDomain() } }
            .flowOn(dispatcher)

    suspend fun summary(): PasskeySummary = summarize(passkeys.first())

    suspend fun put(record: PasskeyRecord): Result<Unit> {
        var replaced = emptyList<PasskeyRecordProto>()
        return write { current ->
            val (sameAccount, others) =
                current.recordsList.partition {
                    record.discoverable &&
                        it.discoverability == DiscoverabilityProto.DISCOVERABILITY_PROTO_DISCOVERABLE &&
                        it.rp.id == record.rp.id.value &&
                        it.user.handle == record.user.handle.b64
                }
            replaced = sameAccount
            current
                .toBuilder()
                .clearRecords()
                .addAllRecords(others)
                .addRecords(record.toProto())
                .build()
        }.mapCatching {
            withContext(dispatcher) { replaced.forEach { deleteKeyMaterial(it.toDomain()) } }
        }
    }

    suspend fun update(record: PasskeyRecord): Result<Unit> =
        edit(record.credentialId) { current, idx ->
            current.toBuilder().setRecords(idx, record.toProto()).build()
        }

    suspend fun setRpName(
        credentialId: CredentialId,
        name: String,
    ): Result<Unit> =
        edit(credentialId) { current, idx ->
            val record = current.getRecords(idx)
            current.toBuilder().setRecords(idx, record.toBuilder().setRp(record.rp.toBuilder().setName(name))).build()
        }

    suspend fun setUser(
        credentialId: CredentialId,
        name: String,
        displayName: String,
    ): Result<Unit> =
        edit(credentialId) { current, idx ->
            val record = current.getRecords(idx)
            val user =
                record.user
                    .toBuilder()
                    .setName(name)
                    .setDisplayName(displayName)
            current.toBuilder().setRecords(idx, record.toBuilder().setUser(user)).build()
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
