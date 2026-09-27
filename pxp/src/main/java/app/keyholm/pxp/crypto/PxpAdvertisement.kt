package app.keyholm.pxp.crypto

import app.keyholm.pxp.CANONICAL_CBOR
import com.upokecenter.cbor.CBORObject

private const val ADVERT_PLAINTEXT_BYTES = 16
private const val NONCE_OFFSET = 1

private const val TRANSPORT_CHANNEL_BLE = 1

class PxpAdvertisement(
    val plaintext: ByteArray,
    val serviceData: ByteArray,
) {
    companion object {
        const val NONCE_BYTES = 10

        fun create(
            qrSecret: ByteArray,
            nonce: ByteArray,
            blePsm: Int,
            createAdvert: (secret: ByteArray, plaintext: ByteArray) -> ByteArray,
        ): PxpAdvertisement {
            val plaintext = ByteArray(ADVERT_PLAINTEXT_BYTES)
            nonce.copyInto(plaintext, NONCE_OFFSET, 0, NONCE_BYTES)
            val suffix = CBORObject.NewMap().Add(TRANSPORT_CHANNEL_BLE, blePsm).EncodeToBytes(CANONICAL_CBOR)
            return PxpAdvertisement(plaintext, createAdvert(qrSecret, plaintext) + suffix)
        }
    }
}
