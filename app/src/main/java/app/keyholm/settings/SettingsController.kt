package app.keyholm.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.util.logger
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.TrustMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

class SettingsController(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val deniedAppsRepo: DeniedNativeAppRepository,
    private val onError: (String) -> Unit,
) {
    private val log = logger()

    val algorithms = AlgorithmSettingsController(::launchEdit)

    private fun launchEdit(transform: suspend (MutablePreferences) -> Unit) {
        scope.launch {
            try {
                repo.dataStore.edit(transform)
            } catch (e: IOException) {
                log.e(e) { "settings write failed" }
                onError(ErrorMessages.SETTINGS_SAVE_FAILED)
            }
        }
    }

    fun dismissDeviceBoundWarning() = launchEdit { it[SettingsKeys.deviceBoundWarningDismissed] = true }

    fun resetSettings() {
        launchEdit { it.clear() }
        scope.launch {
            deniedAppsRepo.clearAccepted().onFailure {
                log.e(it) { "clearing accepted apps failed" }
                onError(ErrorMessages.SETTINGS_SAVE_FAILED)
            }
        }
    }

    fun setIdentityPreference(preference: IdentityPreference) =
        launchEdit {
            it[SettingsKeys.identityPreference] =
                encodeIdentityPreference(preference)
        }

    fun setCreateAuthenticators(policy: AuthenticatorPolicy) =
        launchEdit {
            it[SettingsKeys.createAuthenticators] =
                encodeAuthenticatorPolicy(policy)
        }

    fun setInvalidateOnBiometricEnrollment(value: Boolean) = launchEdit { it[SettingsKeys.invalidateOnBiometricEnrollment] = value }

    fun setCompactView(value: Boolean) = launchEdit { it[SettingsKeys.compactView] = value }

    fun setPreferRpName(value: Boolean) = launchEdit { it[SettingsKeys.preferRpName] = value }

    fun setAppLock(value: Boolean) = launchEdit { it[SettingsKeys.appLock] = value }

    fun setRequireUnlock(value: Boolean) = launchEdit { it[SettingsKeys.requireUnlock] = value }

    fun setNativeAppTrust(mode: TrustMode) = launchEdit { it[SettingsKeys.nativeAppTrust] = encodeNativeAppTrust(mode) }
}
