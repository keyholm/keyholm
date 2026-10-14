package app.keyholm.store.encrypted.crypto

import android.security.KeyStoreException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import app.keyholm.keystore.StoreHmac
import app.keyholm.keystore.StoreHmacKeyManager
import app.keyholm.keystore.StoreKeyKind
import app.keyholm.store.encrypted.exceptions.StoreKeyLostException
import app.keyholm.store.encrypted.exceptions.StoreKeyUnavailableException
import app.keyholm.store.encrypted.exceptions.StoreLockedException
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val SALT_BYTES = 32
private const val AES = "AES"
private val DEVICE_PREFIX = "keyholm.app/v1/device".encodeToByteArray()
private val KEYSET_AAD = "keyholm.app/v1/keyset".encodeToByteArray()

// Wraps DEKs under HMAC(keystore key, prefix ‖ salt)
internal class DekWrapper(
    private val hmacKey: StoreHmacKeyManager,
    private val dispatcher: CoroutineDispatcher,
) {
    private val random = SecureRandom()

    fun generateDeviceKey() = hmacKey.generate(StoreKeyKind.Basic)

    fun newSalt(): ByteString = ByteString.copyFrom(ByteArray(SALT_BYTES).also(random::nextBytes))

    suspend fun key(salt: ByteString): SecretKey =
        withContext(dispatcher) {
            try {
                val hmac =
                    when (val result = hmacKey.hmac(StoreKeyKind.Basic)) {
                        is StoreHmac.Available -> result.mac
                        StoreHmac.KeyNotFound -> throw StoreKeyLostException(IllegalStateException("the device key doesn't exist"))
                    }
                hmac.update(DEVICE_PREFIX)
                hmac.update(salt.toByteArray())
                SecretKeySpec(hmac.doFinal(), AES)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw StoreKeyLostException(e)
            } catch (e: UserNotAuthenticatedException) {
                throw StoreLockedException(e)
            } catch (e: GeneralSecurityException) {
                // The device key needs no user auth, so this code means the device is locked
                if ((e.cause as? KeyStoreException)?.numericErrorCode == KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED) {
                    throw StoreLockedException(e)
                }
                throw StoreKeyUnavailableException(e)
            } catch (e: ProviderException) {
                throw StoreKeyUnavailableException(e)
            }
        }

    fun sealKeyset(
        keyset: KeysetHandle,
        wrapping: SecretKey,
    ): ByteString =
        ByteString.copyFrom(TinkProtoKeysetFormat.serializeEncryptedKeyset(keyset, aesGcm(wrapping), KEYSET_AAD, AeadConfig2026.get()))

    fun openKeyset(
        sealed: ByteString,
        wrapping: SecretKey,
    ): KeysetHandle =
        try {
            TinkProtoKeysetFormat.parseEncryptedKeyset(sealed.toByteArray(), aesGcm(wrapping), KEYSET_AAD, AeadConfig2026.get())
        } catch (e: GeneralSecurityException) {
            throw StoreKeyLostException(e)
        }
}
