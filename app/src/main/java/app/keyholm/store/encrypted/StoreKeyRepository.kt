package app.keyholm.store.encrypted

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import app.keyholm.keystore.HardwareHmacKeyManager
import app.keyholm.store.encrypted.crypto.DekWrapper
import app.keyholm.store.encrypted.crypto.newStoreKeyset
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

internal const val STORE_KEYSET_FILE = "store_keyset.pb"

internal class StoreKeyRepository(
    private val dataStore: DataStore<StoredKeyset?>,
    private val wrapping: DekWrapper,
) : StoreKeys {
    constructor(context: Context) : this(
        DataStoreFactory.create(serializer = StoreKeysetSerializer, produceFile = { context.dataStoreFile(STORE_KEYSET_FILE) }),
        DekWrapper(HardwareHmacKeyManager(), Dispatchers.IO),
    )

    override suspend fun keyset(): KeysetHandle = open(dataStore.data.first() ?: createKeyset())

    private suspend fun open(stored: StoredKeyset): KeysetHandle =
        when (stored) {
            is StoredKeyset.Device -> wrapping.openKeyset(stored.keyset, wrapping.key(stored.salt))
        }

    // Creates the keyset on first use
    private suspend fun createKeyset(): StoredKeyset =
        checkNotNull(dataStore.updateData { it ?: sealNewKeyset() }) { "the update never stores null" }

    private suspend fun sealNewKeyset(): StoredKeyset.Device {
        wrapping.generateDeviceKey()
        val salt = wrapping.newSalt()
        return StoredKeyset.Device(salt, wrapping.sealKeyset(newStoreKeyset(), wrapping.key(salt)))
    }
}
