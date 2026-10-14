package app.keyholm.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.keyholm.keystore.SecureKeyManager.Companion.ANDROID_KEYSTORE
import app.keyholm.util.logger
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

enum class StoreKeyKind { Basic }

sealed interface StoreHmac {
    class Available(
        val mac: Mac,
    ) : StoreHmac

    data object KeyNotFound : StoreHmac
}

interface StoreHmacKeyManager {
    fun generate(kind: StoreKeyKind)

    fun hmac(kind: StoreKeyKind): StoreHmac
}

class HardwareHmacKeyManager : StoreHmacKeyManager {
    private val log = logger()
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun generate(kind: StoreKeyKind) {
        fun generate(strongBox: Boolean) {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias(kind), KeyProperties.PURPOSE_SIGN)
                    .apply {
                        when (kind) {
                            StoreKeyKind.Basic -> setUnlockedDeviceRequired(true)
                        }
                    }.setIsStrongBoxBacked(strongBox)
                    .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(spec)
            generator.generateKey()
        }
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            log.w(e) { "StrongBox unavailable for the store key, falling back to the TEE" }
            generate(strongBox = false)
        }
    }

    override fun hmac(kind: StoreKeyKind): StoreHmac {
        val key = keyStore.getKey(alias(kind), null) as SecretKey? ?: return StoreHmac.KeyNotFound
        return StoreHmac.Available(Mac.getInstance(MAC_ALGORITHM_HMAC_SHA256).apply { init(key) })
    }

    companion object {
        private const val MAC_ALGORITHM_HMAC_SHA256 = "HmacSHA256"

        private fun alias(kind: StoreKeyKind): String =
            when (kind) {
                StoreKeyKind.Basic -> "store_device"
            }
    }
}
