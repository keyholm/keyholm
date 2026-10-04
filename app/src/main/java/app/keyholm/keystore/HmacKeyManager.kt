package app.keyholm.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.keyholm.keystore.SecureKeyManager.Companion.ANDROID_KEYSTORE
import app.keyholm.keystore.SecureKeyManager.Companion.policyFromKeyInfo
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
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

    private val log = logger()
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun generateHmacKey(
        credentialId: CredentialId,
        authenticatorTypes: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): GeneratedHmacKey {
        val alias = alias(credentialId)

        fun generate(strongBox: Boolean): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setUnlockedDeviceRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, authenticatorTypes.keystoreMask)
                    .setInvalidatedByBiometricEnrollment(invalidateOnBiometricEnrollment)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }
        val key =
            try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                log.w(e) { "StrongBox unavailable for the PRF key, falling back to the TEE" }
                deleteKey(credentialId)
                generate(strongBox = false)
            }
        val securityLevel = KeySecurityLevel.ofKeyInfo(hmacKeyInfo(key))
        if (securityLevel == null) {
            deleteKey(credentialId)
            throw SecureElementUnavailableException(PRF_NO_SECURE_HARDWARE)
        }
        return GeneratedHmacKey(key, securityLevel)
    }

    fun generateHmacKeyFor(
        credentialKey: KeyInfo,
        credentialId: CredentialId,
    ): GeneratedHmacKey = generateHmacKey(credentialId, policyFromKeyInfo(credentialKey), credentialKey.isInvalidatedByBiometricEnrollment)

    fun macFor(credentialId: CredentialId): Mac = Mac.getInstance(MAC_ALGORITHM_HMAC_SHA256).apply { init(keyFor(alias(credentialId))) }

    fun allowedAuthenticatorsForHmac(credentialId: CredentialId): AuthenticatorPolicy =
        policyFromKeyInfo(hmacKeyInfo(keyFor(alias(credentialId))))

    private fun alias(credentialId: CredentialId): String = ALIAS_PREFIX + credentialId.b64

    private fun keyFor(alias: String): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey
            ?: throw UnrecoverableKeyException("No key found for alias: $alias")

    private fun hmacKeyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory
            .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

    fun deleteKey(credentialId: CredentialId) {
        val alias = alias(credentialId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    companion object {
        private const val MAC_ALGORITHM_HMAC_SHA256 = "HmacSHA256"
        private const val ALIAS_PREFIX = "hmac_"
        private const val PRF_NO_SECURE_HARDWARE = "This device doesn't support PRF keys with secure hardware."
    }
}
