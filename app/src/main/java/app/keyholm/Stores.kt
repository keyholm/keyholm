package app.keyholm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.keyholm.settings.SETTINGS_STORE_NAME
import app.keyholm.store.EncryptedStores
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.store.proto.MigrationPlaceholdersProto
import app.keyholm.store.proto.PasskeyRecordsProto

// One DataStore per file per process, owned by Application
class Stores(
    context: Context,
) {
    private val encrypted = EncryptedStores(context)

    val passkeys: DataStore<PasskeyRecordsProto> = encrypted.passkeys

    val migrationPlaceholders: DataStore<MigrationPlaceholdersProto> = encrypted.migrationPlaceholders

    val deniedNativeApps: DataStore<DeniedNativeAppsProto> = encrypted.deniedNativeApps

    val settings: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(SETTINGS_STORE_NAME) },
        )

    fun close() = encrypted.close()
}
