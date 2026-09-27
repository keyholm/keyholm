package app.keyholm.pxp.qr

import com.google.common.truth.Truth.assertThat
import com.upokecenter.cbor.CBORObject
import org.junit.jupiter.api.Test

private const val CHUNK_DIGITS = 17
private const val CHUNK_BYTES = 7
private val REMAINDER_DIGITS = mapOf(1 to 3, 2 to 5, 3 to 8, 4 to 10, 5 to 13, 6 to 15)

private val PUBLIC_KEY = byteArrayOf(0x02) + ByteArray(32) { (it + 1).toByte() }
private val SECRET = ByteArray(16) { (it + 1).toByte() }

private fun chunkDigits(
    bytes: ByteArray,
    offset: Int,
    byteCount: Int,
    digitCount: Int,
): String {
    var value = 0L
    for (index in byteCount - 1 downTo 0) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
    }
    return value.toString().padStart(digitCount, '0')
}

private fun digitEncode(bytes: ByteArray): String {
    val out = StringBuilder()
    var offset = 0
    while (bytes.size - offset >= CHUNK_BYTES) {
        out.append(chunkDigits(bytes, offset, CHUNK_BYTES, CHUNK_DIGITS))
        offset += CHUNK_BYTES
    }
    val trailing = bytes.size - offset
    if (trailing > 0) out.append(chunkDigits(bytes, offset, trailing, REMAINDER_DIGITS.getValue(trailing)))
    return out.toString()
}

private fun qrMap(): CBORObject =
    CBORObject
        .NewMap()
        .Add(0, PUBLIC_KEY)
        .Add(1, SECRET)
        .Add(2, 2)

private fun qrUri(map: CBORObject): String = "FIDO:/" + digitEncode(map.EncodeToBytes())

class QrPayloadTest {
    @Test
    fun `digitDecode matches the libwebauthn vector`() {
        assertThat(digitDecode("335311851610699281684828783").getOrThrow()).isEqualTo("hello world".toByteArray())
    }

    @Test
    fun `digitDecode handles every trailing chunk length`() {
        assertThat(digitDecode("001").getOrThrow()).isEqualTo(byteArrayOf(1))
        assertThat(digitDecode("00513").getOrThrow()).isEqualTo(byteArrayOf(1, 2))
        assertThat(digitDecode("00197121").getOrThrow()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(digitDecode("0067305985").getOrThrow()).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(digitDecode("0021542142465").getOrThrow()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(digitDecode("006618611909121").getOrThrow()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun `parses a full payload`() {
        val channels = CBORObject.NewArray().Add(0).Add(1)
        val uri =
            qrUri(
                qrMap()
                    .Add(3, 1757030400L)
                    .Add(4, true)
                    .Add(5, "mc")
                    .Add(6, channels),
            )

        val payload = PxpQrPayload.decode(uri).getOrThrow()

        assertThat(payload.clientPublicKey).isEqualTo(PUBLIC_KEY)
        assertThat(payload.qrSecret).isEqualTo(SECRET)
        assertThat(payload.hint).isEqualTo(PxpFlowHint.MAKE_CREDENTIAL)
        assertThat(payload.channels).containsExactly(PxpChannel.WEBSOCKET, PxpChannel.BLE)
    }

    @Test
    fun `ignores an unknown grease key`() {
        assertThat(PxpQrPayload.decode(qrUri(qrMap().Add(65535, 0))).isSuccess).isTrue()
    }

    @Test
    fun `accepts a scheme normalised to lowercase`() {
        assertThat(PxpQrPayload.decode(qrUri(qrMap()).replace("FIDO:/", "fido:/")).isSuccess).isTrue()
    }

    @Test
    fun `treats an absent channel list as websocket only`() {
        val payload = PxpQrPayload.decode(qrUri(qrMap())).getOrThrow()

        assertThat(payload.channels).containsExactly(PxpChannel.WEBSOCKET)
    }

    @Test
    fun `treats a boolean channel field as websocket only`() {
        val payload = PxpQrPayload.decode(qrUri(qrMap().Add(6, true))).getOrThrow()

        assertThat(payload.channels).containsExactly(PxpChannel.WEBSOCKET)
    }

    @Test
    fun `ignores unknown channel identifiers`() {
        val payload = PxpQrPayload.decode(qrUri(qrMap().Add(6, CBORObject.NewArray().Add(1).Add(7)))).getOrThrow()

        assertThat(payload.channels).containsExactly(PxpChannel.BLE)
    }

    @Test
    fun `defaults an absent hint to getAssertion`() {
        val payload = PxpQrPayload.decode(qrUri(qrMap())).getOrThrow()

        assertThat(payload.hint).isEqualTo(PxpFlowHint.GET_ASSERTION)
    }

    @Test
    fun `defaults an unknown hint to getAssertion`() {
        val payload = PxpQrPayload.decode(qrUri(qrMap().Add(5, "zz"))).getOrThrow()

        assertThat(payload.hint).isEqualTo(PxpFlowHint.GET_ASSERTION)
    }

    @Test
    fun `rejects a non-FIDO scheme`() {
        assertThat(PxpQrPayload.decode("keyholm://migrate").isFailure).isTrue()
    }

    @Test
    fun `rejects non-digit content`() {
        assertThat(PxpQrPayload.decode("FIDO:/00513x").isFailure).isTrue()
    }

    @Test
    fun `rejects an empty payload`() {
        assertThat(PxpQrPayload.decode("FIDO:/").isFailure).isTrue()
    }

    @Test
    fun `rejects an invalid trailing chunk length`() {
        assertThat(PxpQrPayload.decode("FIDO:/0051").isFailure).isTrue()
    }

    @Test
    fun `rejects a wrong-length secret`() {
        val map =
            CBORObject
                .NewMap()
                .Add(0, PUBLIC_KEY)
                .Add(1, ByteArray(15))
                .Add(2, 2)

        assertThat(PxpQrPayload.decode(qrUri(map)).isFailure).isTrue()
    }

    @Test
    fun `rejects an uncompressed public key`() {
        val map =
            CBORObject
                .NewMap()
                .Add(0, ByteArray(33))
                .Add(1, SECRET)
                .Add(2, 2)

        assertThat(PxpQrPayload.decode(qrUri(map)).isFailure).isTrue()
    }

    @Test
    fun `rejects a missing tunnel domain count`() {
        val map = CBORObject.NewMap().Add(0, PUBLIC_KEY).Add(1, SECRET)

        assertThat(PxpQrPayload.decode(qrUri(map)).isFailure).isTrue()
    }

    @Test
    fun `rejects a CBOR array instead of a map`() {
        assertThat(PxpQrPayload.decode(qrUri(CBORObject.NewArray().Add(1))).isFailure).isTrue()
    }

    @Test
    fun `rejects malformed CBOR without throwing`() {
        assertThat(PxpQrPayload.decode("FIDO:/00799").isFailure).isTrue()
    }
}
