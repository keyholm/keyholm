package app.keyholm.keystore

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager

enum class AuthenticatorPolicy(
    val keystoreMask: Int,
    val promptMask: Int,
    val label: String,
) {
    Biometric(
        KeyProperties.AUTH_BIOMETRIC_STRONG,
        BiometricManager.Authenticators.BIOMETRIC_STRONG,
        "Biometrics only",
    ),
    DeviceCredential(
        KeyProperties.AUTH_DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        "Device credential only",
    ),
    Either(
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        "Biometrics + device credential",
    ),
    ;

    companion object {
        fun ofKeyInfo(keyInfo: KeyInfo): AuthenticatorPolicy? {
            val biometric = keyInfo.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0
            val deviceCredential = keyInfo.userAuthenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0
            return when {
                biometric && deviceCredential -> Either
                biometric -> Biometric
                deviceCredential -> DeviceCredential
                else -> null
            }
        }
    }
}
