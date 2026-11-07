package app.keyholm.keystore

import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import app.keyholm.ctap.ClientDataHash
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.WebAuthnAlgorithm
import app.keyholm.util.logger
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

class PasskeyKeyManager {
    data class GeneratedCredential(
        val publicKey: PublicKey,
        val attestationChain: List<ByteArray>,
        val coseAlgorithm: WebAuthnAlgorithm,
        val securityLevel: KeySecurityLevel,
    )

    sealed interface CredentialKeyGeneration {
        data class Generated(
            val credential: GeneratedCredential,
        ) : CredentialKeyGeneration

        data object DevicePropertiesUnavailable : CredentialKeyGeneration
    }

    data class CredentialKeyRequest(
        val credentialId: CredentialId,
        val attestationChallenge: ClientDataHash,
        val algorithm: WebAuthnAlgorithm,
        val authenticators: AuthenticatorPolicy,
        val invalidateOnBiometricEnrollment: Boolean,
        val includeDeviceProperties: Boolean,
    )

    data class GeneratedHmacKey(
        val key: SecretKey,
        val securityLevel: KeySecurityLevel,
    )

    private val log = logger()
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun generateCredentialKey(request: CredentialKeyRequest): CredentialKeyGeneration {
        val alias = alias(request.credentialId)
        val algorithm = request.algorithm
        val keyAlgorithm = keyAlgorithmFor(algorithm)

        fun generate(strongBox: Boolean): CredentialKeyGeneration =
            try {
                CredentialKeyGeneration.Generated(
                    generateAndValidate(
                        keyStore,
                        keyAlgorithm,
                        alias,
                        algorithm,
                        buildCredentialKeySpec(request, strongBox),
                    ),
                )
            } catch (e: ProviderException) {
                if ((e.cause as? KeyStoreException)?.numericErrorCode == KeyStoreException.ERROR_ID_ATTESTATION_FAILURE) {
                    CredentialKeyGeneration.DevicePropertiesUnavailable
                } else {
                    throw e
                }
            }

        fun fallbackFromStrongBox(e: Exception): CredentialKeyGeneration {
            log.w(e) { "StrongBox can't generate ${algorithm.displayName} keys, falling back to the TEE" }
            deleteKeys(request.credentialId)
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

    fun generateHmacKey(
        credentialId: CredentialId,
        authenticatorTypes: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): GeneratedHmacKey {
        val alias = hmacAlias(credentialId)

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
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                generate(strongBox = false)
            }
        val securityLevel = KeySecurityLevel.ofKeyInfo(hmacKeyInfo(key))
        if (securityLevel == null) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            throw SecureElementUnavailableException(PRF_NO_SECURE_HARDWARE)
        }
        return GeneratedHmacKey(key, securityLevel)
    }

    fun generateHmacKeyFor(
        credentialId: CredentialId,
        algorithm: WebAuthnAlgorithm,
    ): GeneratedHmacKey {
        val credentialKey = credentialKeyInfo(keyFor<PrivateKey>(alias(credentialId)), algorithm)
        return generateHmacKey(credentialId, policyFromKeyInfo(credentialKey), credentialKey.isInvalidatedByBiometricEnrollment)
    }

    fun signatureFor(
        credentialId: CredentialId,
        algorithm: WebAuthnAlgorithm,
    ): Signature {
        val jcaAlgorithm =
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> "SHA256withECDSA"
                WebAuthnAlgorithm.ED25519 -> KEY_ALGORITHM_ED25519
                WebAuthnAlgorithm.ML_DSA_65, WebAuthnAlgorithm.ML_DSA_87 -> keyAlgorithmFor(algorithm)
            }
        return Signature.getInstance(jcaAlgorithm).apply { initSign(keyFor<PrivateKey>(alias(credentialId))) }
    }

    fun macFor(credentialId: CredentialId): Mac =
        Mac.getInstance(MAC_ALGORITHM_HMAC_SHA256).apply { init(keyFor<SecretKey>(hmacAlias(credentialId))) }

    fun allowedAuthenticatorsFor(
        credentialId: CredentialId,
        algorithm: WebAuthnAlgorithm,
    ): AuthenticatorPolicy = policyFromKeyInfo(credentialKeyInfo(keyFor<PrivateKey>(alias(credentialId)), algorithm))

    fun allowedAuthenticatorsForHmac(credentialId: CredentialId): AuthenticatorPolicy =
        policyFromKeyInfo(hmacKeyInfo(keyFor<SecretKey>(hmacAlias(credentialId))))

    fun certificateChainPem(credentialId: CredentialId): List<String> =
        keyStore
            .getCertificateChain(alias(credentialId))
            ?.map { cert ->
                val body = Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
                "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
            }.orEmpty()

    fun deleteKeys(credentialId: CredentialId) {
        for (alias in listOf(alias(credentialId), hmacAlias(credentialId))) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private inline fun <reified T : Key> keyFor(alias: String): T =
        keyStore.getKey(alias, null) as? T
            ?: throw UnrecoverableKeyException("No key found for alias: $alias")

    companion object {
        internal const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "key_"
        private const val HMAC_ALIAS_PREFIX = "hmac_"
        private const val KEY_ALGORITHM_ED25519 = "Ed25519"
        private const val KEY_ALGORITHM_ML_DSA_65 = "ML-DSA-65"
        private const val KEY_ALGORITHM_ML_DSA_87 = "ML-DSA-87"
        private const val MAC_ALGORITHM_HMAC_SHA256 = "HmacSHA256"
        private const val PRF_NO_SECURE_HARDWARE = "This device doesn't support PRF keys with secure hardware."

        private fun alias(credentialId: CredentialId): String = ALIAS_PREFIX + credentialId.b64

        private fun hmacAlias(credentialId: CredentialId): String = HMAC_ALIAS_PREFIX + credentialId.b64

        private fun keyAlgorithmFor(algorithm: WebAuthnAlgorithm): String =
            when (algorithm) {
                WebAuthnAlgorithm.ES256 -> KeyProperties.KEY_ALGORITHM_EC
                WebAuthnAlgorithm.ED25519 -> KEY_ALGORITHM_ED25519
                WebAuthnAlgorithm.ML_DSA_65 -> KEY_ALGORITHM_ML_DSA_65
                WebAuthnAlgorithm.ML_DSA_87 -> KEY_ALGORITHM_ML_DSA_87
            }

        private fun buildCredentialKeySpec(
            request: CredentialKeyRequest,
            strongBox: Boolean,
        ): KeyGenParameterSpec {
            val builder =
                KeyGenParameterSpec
                    .Builder(alias(request.credentialId), KeyProperties.PURPOSE_SIGN)
                    .setUnlockedDeviceRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, request.authenticators.keystoreMask)
                    .setInvalidatedByBiometricEnrollment(request.invalidateOnBiometricEnrollment)
                    .setAttestationChallenge(request.attestationChallenge.bytes)
                    .setDevicePropertiesAttestationIncluded(request.includeDeviceProperties)
            when (request.algorithm) {
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
            alias: String,
            algorithm: WebAuthnAlgorithm,
            spec: KeyGenParameterSpec,
        ): GeneratedCredential {
            val generator = KeyPairGenerator.getInstance(keyAlgorithm, ANDROID_KEYSTORE)
            generator.initialize(spec)
            val keyPair = generator.generateKeyPair()

            val securityLevel = KeySecurityLevel.ofKeyInfo(credentialKeyInfo(keyPair.private, algorithm))
            if (securityLevel == null) {
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                throw SecureElementUnavailableException(
                    "This device can't back ${algorithm.displayName} keys with secure hardware.",
                )
            }

            val chain = keyStore.getCertificateChain(alias)?.map { it.encoded }.orEmpty()
            val publicKey = keyStore.getCertificate(alias)?.publicKey ?: keyPair.public
            return GeneratedCredential(publicKey, chain, algorithm, securityLevel)
        }

        private fun policyFromKeyInfo(keyInfo: KeyInfo): AuthenticatorPolicy =
            AuthenticatorPolicy.ofKeyInfo(keyInfo)
                ?: throw UnrecoverableKeyException("Key must require authentication")

        private fun credentialKeyInfo(
            key: PrivateKey,
            algorithm: WebAuthnAlgorithm,
        ): KeyInfo =
            KeyFactory
                .getInstance(keyAlgorithmFor(algorithm), ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java)

        private fun hmacKeyInfo(key: SecretKey): KeyInfo =
            SecretKeyFactory
                .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }
}
