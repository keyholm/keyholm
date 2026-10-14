package app.keyholm.store.encrypted

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.tink.AeadSerializer
import app.keyholm.store.PasskeyRecordsSerializer
import app.keyholm.store.encrypted.crypto.newStoreKeyset
import app.keyholm.store.encrypted.exceptions.StoreLockedException
import app.keyholm.store.isStoreLocked
import app.keyholm.store.proto.PasskeyRecordProto
import app.keyholm.store.proto.PasskeyRecordsProto
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaintextStoreMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores = mutableListOf<DeferredDataStore<*>>()
    private val keys = TestStoreKeys(newStoreKeyset())

    @After
    fun tearDown() = runBlocking { stores.forEach { it.close() } }

    private fun records(credentialId: String) =
        PasskeyRecordsProto
            .newBuilder()
            .addRecords(PasskeyRecordProto.newBuilder().setCredentialId(credentialId))
            .build()

    private fun dataStore(
        oldFile: File,
        newFile: File,
        keys: StoreKeys,
    ): DeferredDataStore<PasskeyRecordsProto> =
        DeferredDataStore<PasskeyRecordsProto>(Dispatchers.IO) { scope ->
            DataStoreFactory.create(
                serializer = AeadSerializer(keys.aead(), PasskeyRecordsSerializer, "passkeys".encodeToByteArray()),
                migrations = listOf(PlaintextStoreMigration(oldFile, newFile, PasskeyRecordsSerializer)),
                scope = scope,
                produceFile = { newFile },
            )
        }.also { stores.add(it) }

    @Test
    fun migratesAndDeletesTheOldFile() =
        runBlocking {
            val original = records("cred-1")
            val oldFile = folder.newFile("passkeys.pb").apply { writeBytes(original.toByteArray()) }
            val newFile = File(folder.root, "vault/passkeys.pb")

            assertThat(dataStore(oldFile, newFile, keys).data.first()).isEqualTo(original)
            assertThat(oldFile.exists()).isFalse()
            assertThat(newFile.exists()).isTrue()
            val marker = "cred-1".encodeToByteArray().asList()
            assertThat(
                newFile
                    .readBytes()
                    .asList()
                    .windowed(marker.size)
                    .none { it == marker },
            ).isTrue()
        }

    @Test
    fun unavailableKeyKeepsThePlaintextForTheNextOpen() =
        runBlocking {
            val original = records("cred-1")
            val oldFile = folder.newFile("passkeys.pb").apply { writeBytes(original.toByteArray()) }
            val newFile = File(folder.root, "vault/passkeys.pb")
            val lockedKeys =
                object : StoreKeys {
                    override suspend fun keyset(): KeysetHandle = throw StoreLockedException(IllegalStateException("locked"))
                }
            val locked = dataStore(oldFile, newFile, lockedKeys)

            val failure = runCatching { locked.data.first() }
            locked.close()

            assertThat(checkNotNull(failure.exceptionOrNull()).isStoreLocked()).isTrue()
            assertThat(oldFile.readBytes()).isEqualTo(original.toByteArray())
            assertThat(newFile.exists()).isFalse()
            assertThat(dataStore(oldFile, newFile, keys).data.first()).isEqualTo(original)
            assertThat(oldFile.exists()).isFalse()
        }

    @Test
    fun corruptPlaintextIsKept() =
        runBlocking {
            val corrupt = byteArrayOf(0x0f)
            val oldFile = folder.newFile("passkeys.pb").apply { writeBytes(corrupt) }
            val newFile = File(folder.root, "vault/passkeys.pb")

            val failure = runCatching { dataStore(oldFile, newFile, keys).data.first() }

            assertThat(failure.exceptionOrNull()).isInstanceOf(CorruptionException::class.java)
            assertThat(oldFile.readBytes()).isEqualTo(corrupt)
            assertThat(newFile.exists()).isFalse()
        }

    @Test
    fun secondOpenDoesNotMigrateAgain() =
        runBlocking {
            val original = records("cred-1")
            val oldFile = folder.newFile("passkeys.pb").apply { writeBytes(original.toByteArray()) }
            val newFile = File(folder.root, "vault/passkeys.pb")
            val first = dataStore(oldFile, newFile, keys)
            first.data.first()
            first.close()

            oldFile.writeBytes(records("cred-2").toByteArray())

            assertThat(dataStore(oldFile, newFile, keys).data.first()).isEqualTo(original)
            assertThat(oldFile.exists()).isFalse()
        }
}
