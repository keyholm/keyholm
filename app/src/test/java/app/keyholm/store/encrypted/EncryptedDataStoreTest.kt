package app.keyholm.store.encrypted

import android.app.Application
import app.keyholm.store.PasskeyRecordsSerializer
import app.keyholm.store.encrypted.crypto.newStoreKeyset
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
