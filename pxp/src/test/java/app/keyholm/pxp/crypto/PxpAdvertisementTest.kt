package app.keyholm.pxp.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private const val QR_SECRET = "00112233445566778899aabbccddeeff"
private const val NONCE = "0102030405060708090a"
private const val PSM = 0x1234
private val FAKE_ADVERT = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

class PxpAdvertisementTest {
    @Test
    fun `create lays out flags nonce and zero fill, then calls createAdvert with them`() {
        var calledWith: Pair<ByteArray, ByteArray>? = null
        val advertisement =
            PxpAdvertisement.create(hex(QR_SECRET), hex(NONCE), PSM) { secret, plaintext ->
                calledWith = secret to plaintext
                FAKE_ADVERT
            }

        assertThat(advertisement.plaintext).hasLength(16)
        assertThat(advertisement.plaintext[0]).isEqualTo(0.toByte())
        assertThat(advertisement.plaintext.copyOfRange(1, 11)).isEqualTo(hex(NONCE))
        assertThat(advertisement.plaintext.copyOfRange(11, 16)).isEqualTo(ByteArray(5))

        assertThat(calledWith?.first).isEqualTo(hex(QR_SECRET))
        assertThat(calledWith?.second).isEqualTo(advertisement.plaintext)
    }

    @Test
    fun `create appends the ble suffix to whatever createAdvert returns`() {
        val advertisement = PxpAdvertisement.create(hex(QR_SECRET), hex(NONCE), PSM) { _, _ -> FAKE_ADVERT }

        assertThat(advertisement.serviceData).isEqualTo(FAKE_ADVERT + hex("a101191234"))
    }

    @Test
    fun `create encodes a single byte psm in four suffix bytes`() {
        val advertisement = PxpAdvertisement.create(hex(QR_SECRET), hex(NONCE), 0x0080) { _, _ -> FAKE_ADVERT }

        assertThat(advertisement.serviceData).isEqualTo(FAKE_ADVERT + hex("a1011880"))
    }
}
