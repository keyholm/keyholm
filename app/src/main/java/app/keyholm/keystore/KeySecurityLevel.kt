package app.keyholm.keystore

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties

enum class KeySecurityLevel(
    val label: String,
    val organizationLabel: String,
) {
    StrongBox("StrongBox", "StrongBox"),
    TrustedEnvironment("TEE", "Trusted Execution Environment"),
    ;

    companion object {
        fun ofKeyInfo(keyInfo: KeyInfo): KeySecurityLevel? =
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> TrustedEnvironment
                else -> null
            }
    }
}
