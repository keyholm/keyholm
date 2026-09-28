package app.keyholm.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.keyholm.keystore.SecureKeyManager.Companion.ANDROID_KEYSTORE
import app.keyholm.keystore.SecureKeyManager.Companion.policyFromKeyInfo
import app.keyholm.webauthn.KeyAlias
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

class HmacKeyManager {
    data class GeneratedHmacKey(
        val key: SecretKey,
        val securityLevel: KeySecurityLevel,
    )

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun generateHmacKey(
        alias: KeyAlias,
        authenticatorTypes: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): GeneratedHmacKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias.value, KeyProperties.PURPOSE_SIGN)
                .setUnlockedDeviceRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, authenticatorTypes.keystoreMask)
                .setInvalidatedByBiometricEnrollment(invalidateOnBiometricEnrollment)
                .setIsStrongBoxBacked(true)
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val key =
            try {
                generator.init(spec)
                generator.generateKey()
            } catch (e: StrongBoxUnavailableException) {
                deleteKey(alias)
                throw SecureElementUnavailableException(PRF_NO_SECURE_ELEMENT, e)
            }
        val securityLevel = KeySecurityLevel.ofKeyInfo(hmacKeyInfo(key))
        if (securityLevel == null) {
            deleteKey(alias)
            throw SecureElementUnavailableException(PRF_NO_SECURE_ELEMENT)
        }
        return GeneratedHmacKey(key, securityLevel)
    }

    fun generateHmacKeyFor(
        credentialKey: KeyInfo,
        alias: KeyAlias,
    ): GeneratedHmacKey = generateHmacKey(alias, policyFromKeyInfo(credentialKey), credentialKey.isInvalidatedByBiometricEnrollment)

    fun macFor(alias: KeyAlias): Mac = Mac.getInstance(MAC_ALGORITHM_HMAC_SHA256).apply { init(keyFor(alias)) }

    fun allowedAuthenticatorsForHmac(alias: KeyAlias): AuthenticatorPolicy = policyFromKeyInfo(hmacKeyInfo(keyFor(alias)))

    private fun keyFor(alias: KeyAlias): SecretKey =
        keyStore.getKey(alias.value, null) as? SecretKey
            ?: throw UnrecoverableKeyException("No key found for alias: ${alias.value}")

    private fun hmacKeyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory
            .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

    private fun deleteKey(alias: KeyAlias) {
        if (keyStore.containsAlias(alias.value)) keyStore.deleteEntry(alias.value)
    }

    companion object {
        private const val MAC_ALGORITHM_HMAC_SHA256 = "HmacSHA256"
        private const val PRF_NO_SECURE_ELEMENT =
            "This device has no secure element (StrongBox). PRF is not supported."
    }
}
