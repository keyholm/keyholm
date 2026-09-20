package app.keyholm.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmPreference
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.MlDsaSupport
import app.keyholm.webauthn.NativeAppTrust
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val SETTINGS_STORE_NAME = "settings"

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_STORE_NAME)

internal fun deleteSettingsStoreFile(context: Context): Boolean {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return !file.exists() || file.delete()
}

internal object SettingsKeys {
    val deviceBoundWarningDismissed = booleanPreferencesKey("device_bound_warning_dismissed")
    val identityPreference = stringPreferencesKey("identity_preference")
    val createAuthenticators = stringPreferencesKey("create_authenticators")
    val invalidateOnBiometricEnrollment = booleanPreferencesKey("invalidate_on_biometric_enrollment")
    val compactView = booleanPreferencesKey("compact_view")
    val preferRpName = booleanPreferencesKey("prefer_rp_name")
    val appLock = booleanPreferencesKey("app_lock")
    val requireUnlock = booleanPreferencesKey("require_unlock")
    val enabledFamilies = stringSetPreferencesKey("enabled_families")
    val preferredAlgorithm = stringPreferencesKey("preferred_algorithm")
    val fallbackAlgorithm = stringPreferencesKey("fallback_algorithm")
    val mlDsaSupport = stringPreferencesKey("ml_dsa_support")
    val nativeAppTrust = stringPreferencesKey("native_app_trust")
}

data class Settings(
    val deviceBoundWarningDismissed: Boolean,
    val identityPreference: IdentityPreference,
    val createAuthenticators: AuthenticatorPolicy,
    val invalidateOnBiometricEnrollment: Boolean,
    val compactView: Boolean,
    val preferRpName: Boolean,
    val appLock: Boolean,
    val requireUnlock: Boolean,
    val enabledFamilies: Set<AlgorithmFamily>,
    val preferredAlgorithm: AlgorithmPreference,
    val fallbackAlgorithm: AlgorithmPreference,
    val mlDsaSupport: MlDsaSupport,
    val nativeAppTrust: NativeAppTrust,
)

class SettingsRepository(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val appContext = context.applicationContext
    internal val dataStore = appContext.settingsDataStore

    val settings: Flow<Settings> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it.toSettings(appContext) }
            .flowOn(dispatcher)

    suspend fun reset(): Result<Unit> =
        try {
            dataStore.edit { it.clear() }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
}

internal fun Preferences.toSettings(context: Context): Settings =
    Settings(
        deviceBoundWarningDismissed = this[SettingsKeys.deviceBoundWarningDismissed] ?: false,
        identityPreference = decodeIdentityPreference(this[SettingsKeys.identityPreference]),
        createAuthenticators = decodeAuthenticatorPolicy(this[SettingsKeys.createAuthenticators]),
        invalidateOnBiometricEnrollment = this[SettingsKeys.invalidateOnBiometricEnrollment] ?: true,
        compactView = this[SettingsKeys.compactView] ?: false,
        preferRpName = this[SettingsKeys.preferRpName] ?: false,
        appLock = this[SettingsKeys.appLock] ?: true,
        requireUnlock = this[SettingsKeys.requireUnlock] ?: true,
        enabledFamilies = decodeEnabledFamilies(this[SettingsKeys.enabledFamilies]),
        preferredAlgorithm = decodeAlgorithmPreference(this[SettingsKeys.preferredAlgorithm]),
        fallbackAlgorithm = decodeAlgorithmPreference(this[SettingsKeys.fallbackAlgorithm]),
        mlDsaSupport = decodeMlDsaSupport(this[SettingsKeys.mlDsaSupport]),
        nativeAppTrust = decodeNativeAppTrust(this[SettingsKeys.nativeAppTrust], context),
    )
