package app.keyholm.store.encrypted.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes
import javax.crypto.SecretKey

private const val NONCE_BYTES = 12
private const val KEY_BYTES = 32
private const val TAG_BYTES = 16

private val PARAMETERS =
    AesGcmParameters
        .builder()
        .setKeySizeBytes(KEY_BYTES)
        .setIvSizeBytes(NONCE_BYTES)
        .setTagSizeBytes(TAG_BYTES)
        .setVariant(AesGcmParameters.Variant.NO_PREFIX)
        .build()

internal fun aesGcm(key: SecretKey): Aead {
    val aesGcmKey =
        AesGcmKey
            .builder()
            .setParameters(PARAMETERS)
            .setKeyBytes(SecretBytes.copyFrom(key.encoded, InsecureSecretKeyAccess.get()))
            .build()
    return KeysetHandle
        .newBuilder()
        .addEntry(KeysetHandle.importKey(aesGcmKey).withRandomId().makePrimary())
        .build()
        .getPrimitive(AeadConfig2026.get(), Aead::class.java)
}
