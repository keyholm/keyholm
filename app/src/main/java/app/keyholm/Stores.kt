package app.keyholm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.keyholm.settings.SETTINGS_STORE_NAME
import app.keyholm.store.DENIED_NATIVE_APPS_STORE_FILE
import app.keyholm.store.DeniedNativeAppsSerializer
import app.keyholm.store.MIGRATION_STORE_FILE
import app.keyholm.store.MigrationPlaceholdersSerializer
import app.keyholm.store.PASSKEY_STORE_FILE
import app.keyholm.store.PasskeyRecordsSerializer
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.store.proto.MigrationPlaceholdersProto
import app.keyholm.store.proto.PasskeyRecordsProto

// One DataStore per file per process, owned by Application
class Stores(
    context: Context,
) {
    val passkeys: DataStore<PasskeyRecordsProto> =
        DataStoreFactory.create(
            serializer = PasskeyRecordsSerializer,
            produceFile = { context.dataStoreFile(PASSKEY_STORE_FILE) },
        )

    val migrationPlaceholders: DataStore<MigrationPlaceholdersProto> =
        DataStoreFactory.create(
            serializer = MigrationPlaceholdersSerializer,
            produceFile = { context.dataStoreFile(MIGRATION_STORE_FILE) },
        )

    val deniedNativeApps: DataStore<DeniedNativeAppsProto> =
        DataStoreFactory.create(
            serializer = DeniedNativeAppsSerializer,
            produceFile = { context.dataStoreFile(DENIED_NATIVE_APPS_STORE_FILE) },
        )

    val settings: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(SETTINGS_STORE_NAME) },
        )
}
