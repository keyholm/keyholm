package app.keyholm.ui.common

import android.content.Context
import androidx.biometric.BiometricManager
import app.keyholm.keystore.AuthenticatorPolicy

sealed interface AuthenticatorsResolution {
    data class Ready(
        val value: AuthenticatorPolicy,
    ) : AuthenticatorsResolution

    data class Unavailable(
        val message: String,
    ) : AuthenticatorsResolution
}

fun resolveCreateAuthenticators(
    context: Context,
    policy: AuthenticatorPolicy,
): AuthenticatorsResolution {
    val canAuthenticate = BiometricManager.from(context).canAuthenticate(policy.promptMask)
    return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
        AuthenticatorsResolution.Ready(policy)
    } else {
        val reason =
            when (canAuthenticate) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> ErrorMessages.BIOMETRICS_NOT_ENROLLED

                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                -> ErrorMessages.BIOMETRICS_UNAVAILABLE

                else -> ErrorMessages.noUsableAuthenticationMethod(canAuthenticate)
            }
        AuthenticatorsResolution.Unavailable(reason)
    }
}
