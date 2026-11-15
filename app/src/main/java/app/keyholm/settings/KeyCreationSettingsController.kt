package app.keyholm.settings

import androidx.datastore.preferences.core.MutablePreferences
import app.keyholm.authenticator.IdentityPreference
import app.keyholm.keystore.AuthenticatorPolicy

class KeyCreationSettingsController(
    private val launchEdit: (suspend (MutablePreferences) -> Unit) -> Unit,
) {
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
}
