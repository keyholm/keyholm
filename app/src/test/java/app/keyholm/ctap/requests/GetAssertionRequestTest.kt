package app.keyholm.ctap.requests

import app.keyholm.ctap.CANONICAL_CBOR
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.RpId
import com.google.common.truth.Truth.assertThat
import com.upokecenter.cbor.CBORObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val CLIENT_DATA_HASH_BYTES = 32

private fun request(build: CBORObject.() -> Unit = {}): ByteArray =
    CBORObject
        .NewMap()
        .apply {
            Add(1, "example.org")
            Add(2, ByteArray(CLIENT_DATA_HASH_BYTES) { it.toByte() })
            build()
        }.EncodeToBytes(CANONICAL_CBOR)

private fun parsed(bytes: ByteArray): GetAssertionRequest =
    when (val result = parseGetAssertion(bytes)) {
        is Ctap2Result.Ok -> result.value
        is Ctap2Result.Rejected -> error("expected a parsed request, got ${result.status}")
    }

private fun rejection(bytes: ByteArray): Ctap2Status =
    when (val result = parseGetAssertion(bytes)) {
        is Ctap2Result.Rejected -> result.status
        is Ctap2Result.Ok -> error("expected a rejection, got a parsed request")
    }

@RunWith(RobolectricTestRunner::class)
class GetAssertionRequestTest {
    @Test
    fun `parses the required parameters with no allowList`() {
        val request = parsed(request())

        assertThat(request.rpId).isEqualTo(RpId("example.org"))
        assertThat(request.clientDataHash.bytes).hasLength(CLIENT_DATA_HASH_BYTES)
        assertThat(request.allowIds).isNull()
    }

    @Test
    fun `reads the allowList credential ids`() {
        val request =
            parsed(
                request {
                    val a = CBORObject.NewMap().Add("id", byteArrayOf(1, 2)).Add("type", "public-key")
                    val b = CBORObject.NewMap().Add("id", byteArrayOf(3, 4)).Add("type", "public-key")
                    Add(3, CBORObject.NewArray().Add(a).Add(b))
                },
            )

        assertThat(request.allowIds).containsExactly(CredentialId.of(byteArrayOf(1, 2)), CredentialId.of(byteArrayOf(3, 4)))
    }

    @Test
    fun `treats an empty allowList as absent`() {
        assertThat(parsed(request { Add(3, CBORObject.NewArray()) }).allowIds).isNull()
    }

    @Test
    fun `rejects the rk option`() {
        assertThat(rejection(request { Add(5, CBORObject.NewMap().Add("rk", true)) }))
            .isEqualTo(Ctap2Status.UNSUPPORTED_OPTION)
    }

    @Test
    fun `accepts up false`() {
        parsed(request { Add(5, CBORObject.NewMap().Add("up", false)) })
    }

    @Test
    fun `rejects a pinUvAuthParam without a pinUvAuthProtocol`() {
        assertThat(rejection(request { Add(6, byteArrayOf(1)) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects a pinUvAuthParam because no protocol is supported`() {
        assertThat(rejection(request { Add(6, byteArrayOf(1)).Add(7, 2) }))
            .isEqualTo(Ctap2Status.INVALID_PARAMETER)
    }

    @Test
    fun `rejects a missing rpId`() {
        assertThat(rejection(request { Remove(CBORObject.FromObject(1)) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects a missing clientDataHash`() {
        assertThat(rejection(request { Remove(CBORObject.FromObject(2)) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects something that is not CBOR`() {
        assertThat(rejection(byteArrayOf(0x1f, 0x1f))).isEqualTo(Ctap2Status.INVALID_CBOR)
    }

    @Test
    fun `rejects CBOR that is not a map`() {
        assertThat(rejection(CBORObject.NewArray().EncodeToBytes())).isEqualTo(Ctap2Status.INVALID_CBOR)
    }
}
