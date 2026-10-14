package app.keyholm.store.encrypted

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import app.keyholm.keystore.HardwareHmacKeyManager
import app.keyholm.store.encrypted.crypto.DekWrapper
import app.keyholm.store.encrypted.crypto.newStoreKeyset
import app.keyholm.store.proto.DeviceKeysetProto
import app.keyholm.store.proto.StoreKeysetProto
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

internal const val STORE_KEYSET_FILE = "store_keyset.pb"

internal class StoreKeyRepository(
    private val dataStore: DataStore<StoreKeysetProto>,
    private val wrapping: DekWrapper,
) : StoreKeys {
    constructor(context: Context) : this(
        DataStoreFactory.create(serializer = StoreKeysetSerializer, produceFile = { context.dataStoreFile(STORE_KEYSET_FILE) }),
        DekWrapper(HardwareHmacKeyManager(), Dispatchers.IO),
    )

    override suspend fun keyset(): KeysetHandle {
        val sealed = sealedKeyset()
        return wrapping.openKeyset(sealed.keyset, wrapping.key(sealed.salt))
    }

    // Creates the keyset on first use
    private suspend fun sealedKeyset(): DeviceKeysetProto {
        val stored = dataStore.data.first()
        if (stored.protectionCase == StoreKeysetProto.ProtectionCase.DEVICE) return stored.device
        return dataStore
            .updateData {
                if (it.protectionCase == StoreKeysetProto.ProtectionCase.DEVICE) it else sealNewKeyset()
            }.device
    }

    private suspend fun sealNewKeyset(): StoreKeysetProto {
        wrapping.generateDeviceKey()
        val salt = wrapping.newSalt()
        return StoreKeysetProto
            .newBuilder()
            .setDevice(
                DeviceKeysetProto
                    .newBuilder()
                    .setSalt(salt)
                    .setKeyset(wrapping.sealKeyset(newStoreKeyset(), wrapping.key(salt))),
            ).build()
    }
}
