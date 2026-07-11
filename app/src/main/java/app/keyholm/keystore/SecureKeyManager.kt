package app.keyholm.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import app.keyholm.webauthn.ClientDataHash
import app.keyholm.webauthn.KeyAlias
import app.keyholm.webauthn.WebAuthnAlgorithm
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

class SecureElementUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SecureKeyManager {
    data class GeneratedCredential(
        val publicKey: PublicKey,
        val attestationChain: List<ByteArray>,
        val coseAlgorithm: WebAuthnAlgorithm,
        val securityLevel: KeySecurityLevel,
    )

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun generateCredentialKey(
        alias: KeyAlias,
        attestationChallenge: ClientDataHash,
        algorithm: WebAuthnAlgorithm,
        authenticatorTypes: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): GeneratedCredential {
        val keyAlgorithm = keyAlgorithmFor(algorithm)

        fun generate(strongBox: Boolean) =
            generateAndValidate(
                keyStore,
                keyAlgorithm,
                alias,
                algorithm,
                buildCredentialKeySpec(
                    alias,
                    attestationChallenge,
                    algorithm,
                    authenticatorTypes,
                    invalidateOnBiometricEnrollment,
                    strongBox,
                ),
            )

        fun fallbackFromStrongBox(e: Exception): GeneratedCredential {
            deleteKey(alias)
            if (algorithm == WebAuthnAlgorithm.ES256) {
                throw SecureElementUnavailableException(
                    "This device has no secure element (StrongBox). Hardware passkeys are not supported.",
                    e,
                )
            }
            return generate(strongBox = false)
        }

        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            fallbackFromStrongBox(e)
        } catch (e: ProviderException) {
            // StrongBox that can't do this algorithm reports a KeyStoreException, not a unavailable exception.
            if ((e.cause as? KeyStoreException)?.isSystemError == true) fallbackFromStrongBox(e) else throw e
        } catch (e: InvalidAlgorithmParameterException) {
            fallbackFromStrongBox(e)
        }
    }

    private inline fun <reified T : Key> keyFor(alias: KeyAlias): T =
        keyStore.getKey(alias.value, null) as? T
            ?: throw UnrecoverableKeyException("No key found for alias: ${alias.value}")

    data class GeneratedHmacKey(
        val key: SecretKey,
        val securityLevel: KeySecurityLevel,
    )

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

    fun macFor(alias: KeyAlias): Mac = Mac.getInstance(MAC_ALGORITHM_HMAC_SHA256).apply { init(keyFor<SecretKey>(alias)) }

    fun signatureFor(
        alias: KeyAlias,
        algorithm: WebAuthnAlgorithm,
    ): Signature {
        val jcaAlgorithm =
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> "SHA256withECDSA"
                WebAuthnAlgorithm.ED25519 -> KEY_ALGORITHM_ED25519
                WebAuthnAlgorithm.ML_DSA_65, WebAuthnAlgorithm.ML_DSA_87 -> keyAlgorithmFor(algorithm)
            }
        return Signature.getInstance(jcaAlgorithm).apply { initSign(keyFor<PrivateKey>(alias)) }
    }

    fun allowedAuthenticatorsFor(
        alias: KeyAlias,
        algorithm: WebAuthnAlgorithm,
    ): AuthenticatorPolicy {
        val keyInfo =
            KeyFactory
                .getInstance(keyAlgorithmFor(algorithm), ANDROID_KEYSTORE)
                .getKeySpec(keyFor<PrivateKey>(alias), KeyInfo::class.java)
        return policyFromKeyInfo(keyInfo)
    }

    fun allowedAuthenticatorsForHmac(alias: KeyAlias): AuthenticatorPolicy = policyFromKeyInfo(hmacKeyInfo(keyFor<SecretKey>(alias)))

    private fun hmacKeyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory
            .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

    fun deleteKey(alias: KeyAlias) {
        if (keyStore.containsAlias(alias.value)) keyStore.deleteEntry(alias.value)
    }

    fun deleteAllKeys() {
        keyStore.aliases().toList().forEach { keyStore.deleteEntry(it) }
    }

    fun certificateChainPem(alias: KeyAlias): List<String> =
        keyStore.getCertificateChain(alias.value)?.map { cert ->
            val body = Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
            "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
        } ?: emptyList()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM_ED25519 = "Ed25519"
        private const val KEY_ALGORITHM_ML_DSA_65 = "ML-DSA-65"
        private const val KEY_ALGORITHM_ML_DSA_87 = "ML-DSA-87"
        private const val MAC_ALGORITHM_HMAC_SHA256 = "HmacSHA256"
        private const val PRF_NO_SECURE_ELEMENT =
            "This device has no secure element (StrongBox). PRF is not supported."

        private fun keyAlgorithmFor(algorithm: WebAuthnAlgorithm): String =
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> KeyProperties.KEY_ALGORITHM_EC
                WebAuthnAlgorithm.ED25519 -> KEY_ALGORITHM_ED25519
                WebAuthnAlgorithm.ML_DSA_65 -> KEY_ALGORITHM_ML_DSA_65
                WebAuthnAlgorithm.ML_DSA_87 -> KEY_ALGORITHM_ML_DSA_87
            }

        private fun buildCredentialKeySpec(
            alias: KeyAlias,
            attestationChallenge: ClientDataHash,
            algorithm: WebAuthnAlgorithm,
            authenticatorTypes: AuthenticatorPolicy,
            invalidateOnBiometricEnrollment: Boolean,
            strongBox: Boolean,
        ): KeyGenParameterSpec {
            val builder =
                KeyGenParameterSpec
                    .Builder(alias.value, KeyProperties.PURPOSE_SIGN)
                    .setUnlockedDeviceRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, authenticatorTypes.keystoreMask)
                    .setInvalidatedByBiometricEnrollment(invalidateOnBiometricEnrollment)
                    .setAttestationChallenge(attestationChallenge.bytes)
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> {
                    builder
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                }

                WebAuthnAlgorithm.ED25519 -> {
                    builder
                        .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                        .setDigests(KeyProperties.DIGEST_NONE)
                }

                WebAuthnAlgorithm.ML_DSA_65, WebAuthnAlgorithm.ML_DSA_87 -> {
                    builder.setDigests(KeyProperties.DIGEST_NONE)
                }
            }
            if (strongBox) builder.setIsStrongBoxBacked(true)
            return builder.build()
        }

        private fun generateAndValidate(
            keyStore: KeyStore,
            keyAlgorithm: String,
            alias: KeyAlias,
            algorithm: WebAuthnAlgorithm,
            spec: KeyGenParameterSpec,
        ): GeneratedCredential {
            val generator = KeyPairGenerator.getInstance(keyAlgorithm, ANDROID_KEYSTORE)
            generator.initialize(spec)
            val keyPair = generator.generateKeyPair()

            val keyInfo =
                KeyFactory
                    .getInstance(keyAlgorithm, ANDROID_KEYSTORE)
                    .getKeySpec(keyPair.private, KeyInfo::class.java)
            val securityLevel = KeySecurityLevel.ofKeyInfo(keyInfo)
            if (securityLevel == null) {
                if (keyStore.containsAlias(alias.value)) keyStore.deleteEntry(alias.value)
                throw SecureElementUnavailableException(
                    "This device can't back ${algorithm.displayName} keys with secure hardware.",
                )
            }

            val chain = keyStore.getCertificateChain(alias.value)?.map { it.encoded } ?: emptyList()
            val publicKey = keyStore.getCertificate(alias.value)?.publicKey ?: keyPair.public
            return GeneratedCredential(publicKey, chain, algorithm, securityLevel)
        }

        private fun policyFromKeyInfo(keyInfo: KeyInfo): AuthenticatorPolicy =
            AuthenticatorPolicy.ofKeyInfo(keyInfo)
                ?: throw UnrecoverableKeyException("Key must require authentication")

        fun isSecureElementAvailable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        fun isTeeAvailable(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE)

        fun isAlgorithmAvailable(
            context: Context,
            algorithm: WebAuthnAlgorithm,
        ): Boolean =
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> isSecureElementAvailable(context)

                WebAuthnAlgorithm.ED25519,
                WebAuthnAlgorithm.ML_DSA_65,
                WebAuthnAlgorithm.ML_DSA_87,
                -> isTeeAvailable(context)
            }
    }
}
