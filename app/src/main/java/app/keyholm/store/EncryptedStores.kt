package app.keyholm.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import app.keyholm.store.encrypted.DeferredDataStore
import app.keyholm.store.encrypted.StoreKeyRepository
import app.keyholm.store.encrypted.encryptedDataStore
import app.keyholm.store.encrypted.exceptions.StoreKeyLostException
import app.keyholm.store.encrypted.exceptions.StoreLockedException
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.store.proto.MigrationPlaceholdersProto
import app.keyholm.store.proto.PasskeyRecordsProto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class EncryptedStores(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val storeKeys = StoreKeyRepository(context)
    private val closing = CoroutineScope(dispatcher + SupervisorJob())

    val passkeys: DeferredDataStore<PasskeyRecordsProto> =
        encryptedDataStore(context, PASSKEY_STORE_FILE, PasskeyRecordsSerializer, storeKeys, dispatcher)

    val migrationPlaceholders: DeferredDataStore<MigrationPlaceholdersProto> =
        encryptedDataStore(context, MIGRATION_STORE_FILE, MigrationPlaceholdersSerializer, storeKeys, dispatcher)

    val deniedNativeApps: DeferredDataStore<DeniedNativeAppsProto> =
        encryptedDataStore(context, DENIED_NATIVE_APPS_STORE_FILE, DeniedNativeAppsSerializer, storeKeys, dispatcher)

    fun close() {
        closing.launch {
            passkeys.close()
            migrationPlaceholders.close()
            deniedNativeApps.close()
        }
    }
}

internal fun Throwable.isStoreLocked(): Boolean = generateSequence(this) { it.cause }.any { it is StoreLockedException }

internal fun Throwable.isStoreUnrecoverable(): Boolean =
    generateSequence(this) { it.cause }.any { it is StoreKeyLostException || it is CorruptionException }
