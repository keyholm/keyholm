package app.keyholm.store.encrypted

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

private const val STORE_AAD_PREFIX = "keyholm.app/v1/store"
private const val ENCRYPTED_DIR = "encrypted"

internal fun encryptedStoreFile(
    context: Context,
    name: String,
): File = context.dataStoreFile("$ENCRYPTED_DIR/$name")

internal fun storeAad(name: String): ByteArray = "$STORE_AAD_PREFIX/$name".encodeToByteArray()

internal fun <T> encryptedDataStore(
    context: Context,
    name: String,
    serializer: Serializer<T>,
    keys: StoreKeys,
    dispatcher: CoroutineDispatcher,
): DeferredDataStore<T> {
    val file = encryptedStoreFile(context, name)
    return DeferredDataStore(dispatcher) { scope ->
        DataStoreFactory.create(
            serializer = AeadSerializer(keys.aead(), serializer, storeAad(name)),
            migrations =
                listOf(
                    PlaintextStoreMigration(
                        oldFile = context.dataStoreFile(name),
                        newFile = file,
                        serializer = serializer,
                    ),
                ),
            scope = scope,
            produceFile = { file },
        )
    }
}
