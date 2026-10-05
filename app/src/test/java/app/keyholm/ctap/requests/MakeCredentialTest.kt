package app.keyholm.ctap.requests

import app.keyholm.ctap.CANONICAL_CBOR
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Response
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.RpId
import app.keyholm.ctap.UserHandle
import app.keyholm.ctap.WebAuthnAlgorithm
import app.keyholm.provider.AttestationOffer
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
            Add(1, ByteArray(CLIENT_DATA_HASH_BYTES) { it.toByte() })
            Add(2, CBORObject.NewMap().Add("id", "example.org").Add("name", "Example Relying Party"))
            Add(
                3,
                CBORObject
                    .NewMap()
                    .Add("id", byteArrayOf(1, 2, 3))
                    .Add("name", "Mario Rossi")
                    .Add("displayName", "Mario R"),
            )
            Add(4, CBORObject.NewArray().Add(CBORObject.NewMap().Add("alg", -7).Add("type", "public-key")))
            build()
        }.EncodeToBytes(CANONICAL_CBOR)

private fun parsed(bytes: ByteArray): MakeCredentialRequest =
    when (val result = parseMakeCredential(bytes)) {
        is Ctap2Result.Ok -> result.value
        is Ctap2Result.Rejected -> error("expected a parsed request, got ${result.status}")
    }

private fun rejection(bytes: ByteArray): Ctap2Status =
    when (val result = parseMakeCredential(bytes)) {
        is Ctap2Result.Rejected -> result.status
        is Ctap2Result.Ok -> error("expected a rejection, got a parsed request")
    }

@RunWith(RobolectricTestRunner::class)
class MakeCredentialTest {
    @Test
    fun `parses the required parameters`() {
        val request = parsed(request())

        assertThat(request.clientDataHash.bytes).hasLength(CLIENT_DATA_HASH_BYTES)
        assertThat(request.info.rp.id).isEqualTo(RpId("example.org"))
        assertThat(request.info.rp.name).isEqualTo("Example Relying Party")
        assertThat(request.info.user.handle).isEqualTo(UserHandle.of(byteArrayOf(1, 2, 3)))
        assertThat(request.info.user.name).isEqualTo("Mario Rossi")
        assertThat(request.info.user.displayName).isEqualTo("Mario R")
        assertThat(request.algorithms).containsExactly(WebAuthnAlgorithm.ES256)
        assertThat(request.excludeIds).isEmpty()
        assertThat(request.attestation).isEqualTo(AttestationOffer.Ask)
    }

    @Test
    fun `optional names default to empty`() {
        val request =
            parsed(
                request {
                    Set(2, CBORObject.NewMap().Add("id", "example.org"))
                    Set(3, CBORObject.NewMap().Add("id", byteArrayOf(9)))
                },
            )

        assertThat(request.info.rp.name).isEmpty()
        assertThat(request.info.user.name).isEmpty()
        assertThat(request.info.user.displayName).isEmpty()
    }

    @Test
    fun `keeps the platform's algorithm order and drops unknown credential types`() {
        val request =
            parsed(
                request {
                    Set(
                        4,
                        CBORObject
                            .NewArray()
                            .Add(CBORObject.NewMap().Add("alg", -8).Add("type", "public-key"))
                            .Add(CBORObject.NewMap().Add("alg", -37).Add("type", "something-else"))
                            .Add(CBORObject.NewMap().Add("alg", -7).Add("type", "public-key")),
                    )
                },
            )

        assertThat(request.algorithms).containsExactly(WebAuthnAlgorithm.ED25519, WebAuthnAlgorithm.ES256).inOrder()
    }

    @Test
    fun `drops algorithms Keyholm does not implement`() {
        val request =
            parsed(
                request {
                    Set(
                        4,
                        CBORObject
                            .NewArray()
                            .Add(CBORObject.NewMap().Add("alg", -257).Add("type", "public-key"))
                            .Add(CBORObject.NewMap().Add("alg", -7).Add("type", "public-key")),
                    )
                },
            )

        assertThat(request.algorithms).containsExactly(WebAuthnAlgorithm.ES256)
    }

    @Test
    fun `reads the exclude list and the attestation formats preference`() {
        val request =
            parsed(
                request {
                    val descriptor = CBORObject.NewMap().Add("id", byteArrayOf(4, 5)).Add("type", "public-key")
                    Add(5, CBORObject.NewArray().Add(descriptor))
                    Add(0x0B, CBORObject.NewArray().Add("none"))
                },
            )

        assertThat(request.excludeIds).containsExactly(CredentialId.of(byteArrayOf(4, 5)))
        assertThat(request.attestation).isEqualTo(AttestationOffer.NotRequested)
    }

    @Test
    fun `rejects a missing clientDataHash`() {
        assertThat(rejection(request { Remove(CBORObject.FromObject(1)) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects a missing rp id`() {
        assertThat(rejection(request { Set(2, CBORObject.NewMap().Add("name", "Example")) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects a missing pubKeyCredParams`() {
        assertThat(rejection(request { Remove(CBORObject.FromObject(4)) }))
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

    @Test
    fun `the rk option decides whether the credential is discoverable`() {
        assertThat(parsed(request { Add(7, CBORObject.NewMap().Add("rk", true)) }).info.discoverable).isTrue()
        assertThat(parsed(request()).info.discoverable).isFalse()
    }

    @Test
    fun `rejects up false`() {
        assertThat(rejection(request { Add(7, CBORObject.NewMap().Add("up", false)) }))
            .isEqualTo(Ctap2Status.INVALID_OPTION)
    }

    @Test
    fun `accepts the rk and uv options`() {
        parsed(request { Add(7, CBORObject.NewMap().Add("rk", true).Add("uv", true)) })
    }

    @Test
    fun `rejects an option that is not a boolean`() {
        assertThat(rejection(request { Add(7, CBORObject.NewMap().Add("uv", 1)) }))
            .isEqualTo(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    }

    @Test
    fun `rejects a pinUvAuthParam without a pinUvAuthProtocol`() {
        assertThat(rejection(request { Add(8, byteArrayOf(1)) }))
            .isEqualTo(Ctap2Status.MISSING_PARAMETER)
    }

    @Test
    fun `rejects a pinUvAuthParam because no protocol is supported`() {
        assertThat(rejection(request { Add(8, byteArrayOf(1)).Add(9, 2) }))
            .isEqualTo(Ctap2Status.INVALID_PARAMETER)
    }

    @Test
    fun `rejects a pubKeyCredParams entry without an alg`() {
        assertThat(rejection(request { Set(4, CBORObject.NewArray().Add(CBORObject.NewMap().Add("type", "public-key"))) }))
            .isEqualTo(Ctap2Status.INVALID_CBOR)
    }

    @Test
    fun `rejects a pubKeyCredParams alg that is not an integer`() {
        val entry = CBORObject.NewMap().Add("alg", "-7").Add("type", "public-key")
        assertThat(rejection(request { Set(4, CBORObject.NewArray().Add(entry)) }))
            .isEqualTo(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    }

    @Test
    fun `skips an alg outside the Int range`() {
        val request =
            parsed(
                request {
                    Set(
                        4,
                        CBORObject
                            .NewArray()
                            .Add(CBORObject.NewMap().Add("alg", 1L shl 40).Add("type", "public-key"))
                            .Add(CBORObject.NewMap().Add("alg", -7).Add("type", "public-key")),
                    )
                },
            )

        assertThat(request.algorithms).containsExactly(WebAuthnAlgorithm.ES256)
    }

    @Test
    fun `no prf extension leaves prf unrequested`() {
        val request = parsed(request())

        assertThat(request.info.prfRequested).isFalse()
        assertThat(request.prfEval).isNull()
    }

    @Test
    fun `reads the prf extension and its eval salts`() {
        val eval = CBORObject.NewMap().Add("first", ByteArray(32) { 1 }).Add("second", ByteArray(32) { 2 })
        val request = parsed(request { Add(6, CBORObject.NewMap().Add("prf", CBORObject.NewMap().Add("eval", eval))) })

        assertThat(request.info.prfRequested).isTrue()
        assertThat(request.prfEval?.first).isEqualTo(ByteArray(32) { 1 })
        assertThat(request.prfEval?.second).isEqualTo(ByteArray(32) { 2 })
    }

    @Test
    fun `reads a prf extension without eval`() {
        val request = parsed(request { Add(6, CBORObject.NewMap().Add("prf", CBORObject.NewMap())) })

        assertThat(request.info.prfRequested).isTrue()
        assertThat(request.prfEval).isNull()
    }

    @Test
    fun `rejects a prf salt that is not 32 bytes`() {
        val eval = CBORObject.NewMap().Add("first", ByteArray(16))
        assertThat(rejection(request { Add(6, CBORObject.NewMap().Add("prf", CBORObject.NewMap().Add("eval", eval))) }))
            .isEqualTo(Ctap2Status.INVALID_PARAMETER)
    }

    @Test
    fun `a response is the status byte followed by the body`() {
        val body = CBORObject.NewMap().Add(1, "none")

        assertThat(Ctap2Response(body).encode())
            .isEqualTo(byteArrayOf(0) + body.EncodeToBytes(CANONICAL_CBOR))
        assertThat(Ctap2Response(Ctap2Status.OPERATION_DENIED).encode()).isEqualTo(byteArrayOf(0x27))
    }
}
