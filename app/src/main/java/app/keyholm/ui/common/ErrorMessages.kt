package app.keyholm.ui.common

import android.security.KeyStoreException

internal object ErrorMessages {
    const val CALLER_UNVERIFIED = "Couldn't verify this app is allowed to create passkeys. Check settings."
    const val NATIVE_APP_TRUST_DENIED =
        "Keyholm is set to block native apps from using passkeys."

    const val SAVE_FAILED = "Failed to save passkey, check device storage."
    const val UPDATE_FAILED = "Failed to update passkey, check device storage."
    const val SETTINGS_SAVE_FAILED = "Failed to update setting, check device storage."
    const val DENIAL_RECORD_FAILED = "Failed to record blocked app, check device storage."
    const val ICON_PACK_NOT_A_PACK = "That file isn't an icon pack."
    const val ICON_PACK_INSTALL_FAILED = "Failed to save icon pack, check device storage."
    const val ICON_PACK_REMOVE_FAILED = "Failed to remove icon pack, check device storage."
    const val ICON_PACK_LOAD_FAILED = "Couldn't load the installed icon pack."

    const val PLACEHOLDER_CLEANUP_FAILED =
        "Passkey created but failed to remove placeholder."
    const val SECURITY_ERROR_CREATE = "Couldn't create passkey due to a security error."
    const val DEVICE_PROPERTIES_UNSUPPORTED = "Your device doesn't support device properties attestation."
    const val KEY_INVALIDATED = "Passkey invalidated because of lock screen or biometrics changes."

    const val MALFORMED_REQUEST = "Invalid passkey request."
    const val MIGRATION_LINK_MALFORMED = "Import list is corrupt."
    const val CHECK_EXISTING_FAILED = "Couldn't check for existing passkeys."
    const val DEVICE_BOUND_NOT_SUPPORTED = "This site doesn't support Keyholm's device-bound passkeys."
    const val ALREADY_REGISTERED = "You already have a passkey on this device for this identity and site."
    const val BIOMETRICS_NOT_ENROLLED = "Only biometrics are allowed but none were found."
    const val BIOMETRICS_UNAVAILABLE = "Only biometrics are allowed but no biometric device is available."
    private const val HARDWARE_ERROR = "A hardware error occurred."
    const val CREATE_NO_SIGNATURE = "Couldn't create passkey."
    const val SIGN_FAILED_CREATE = "Failed to sign passkey."

    const val LOOKUP_FAILED = "Couldn't look up this passkey."
    const val SECURITY_ERROR_GET = "Couldn't sign in due to a security error."
    const val GET_NO_SIGNATURE = "Couldn't sign in with this passkey."
    const val SIGN_FAILED_GET = "Couldn't sign this sign-in request."

    const val DELETE_KEY_UNAVAILABLE = "Couldn't delete this passkey due to a security error."

    fun noUsableAuthenticationMethod(code: Int): String = "No usable authentication method available (code $code)"

    fun hardwareError(e: Throwable): String =
        HARDWARE_ERROR +
            when ((e.cause as? KeyStoreException)?.getRetryPolicy()) {
                KeyStoreException.RETRY_WITH_EXPONENTIAL_BACKOFF -> " Please try again in a moment."
                KeyStoreException.RETRY_WHEN_CONNECTIVITY_AVAILABLE -> " Please try again once you are back online."
                KeyStoreException.RETRY_AFTER_NEXT_REBOOT -> " Please try again after an update and restart."
                else -> ""
            }
}
