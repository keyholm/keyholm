package app.keyholm.store.encrypted

import android.app.Application
import androidx.datastore.dataStoreFile
import app.keyholm.store.PasskeyRecordsSerializer
import app.keyholm.store.encrypted.crypto.newStoreKeyset
import app.keyholm.store.encrypted.crypto.storeAead
import app.keyholm.store.proto.PasskeyRecordProto
import app.keyholm.store.proto.PasskeyRecordsProto
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EncryptedDataStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val keyset = newStoreKeyset()
    private val records =
        PasskeyRecordsProto
            .newBuilder()
            .addRecords(PasskeyRecordProto.newBuilder().setCredentialId("cred-1"))
            .build()

    private fun open(name: String): PasskeyRecordsProto =
        PasskeyRecordsProto.parseFrom(
            keyset.storeAead().decrypt(encryptedStoreFile(context, name).readBytes(), storeAad(name)),
        )

    @Test
    fun migratesPlaintextIntoTheEncryptedStore() =
        runBlocking {
            val name = "store_${System.nanoTime()}.pb"
            val plaintext =
                context.dataStoreFile(name).apply {
                    parentFile?.mkdirs()
                    writeBytes(records.toByteArray())
                }

            val store = encryptedDataStore(context, name, PasskeyRecordsSerializer, TestStoreKeys(keyset), Dispatchers.IO)

            assertThat(store.data.first()).isEqualTo(records)
            assertThat(plaintext.exists()).isFalse()
            assertThat(open(name)).isEqualTo(records)
        }

    @Test
    fun closeReopensTheKeysetOnTheNextRead() =
        runBlocking {
            val name = "store_${System.nanoTime()}.pb"
            var opens = 0
            val counting =
                object : StoreKeys {
                    override suspend fun keyset(): KeysetHandle {
                        opens++
                        return keyset
                    }
                }
            val store = encryptedDataStore(context, name, PasskeyRecordsSerializer, counting, Dispatchers.IO)
            store.updateData { records }
            store.data.first()
            assertThat(opens).isEqualTo(1)

            store.close()

            assertThat(store.data.first()).isEqualTo(records)
            assertThat(opens).isEqualTo(2)
        }
}
