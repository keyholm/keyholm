package app.keyholm.ctap

import app.keyholm.pxp.ctap.MAX_CTAP_MESSAGE_BYTES
import app.keyholm.pxp.ctap.PxpAuthenticator
import app.keyholm.webauthn.WebAuthn
import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject

private const val GET_INFO_VERSIONS = 1
private const val GET_INFO_AAGUID = 3
private const val GET_INFO_MAX_MSG_SIZE = 5

internal class CtapAuthenticator : PxpAuthenticator {
    override suspend fun getInfo(): ByteArray =
        CBORObject
            .NewMap()
            .Add(GET_INFO_VERSIONS, CBORObject.NewArray().Add("FIDO_2_0"))
            .Add(GET_INFO_AAGUID, WebAuthn.KEYHOLM_AAGUID.toByteArray())
            .Add(GET_INFO_MAX_MSG_SIZE, MAX_CTAP_MESSAGE_BYTES)
            .EncodeToBytes(CBOREncodeOptions("ctap2canonical=true"))

    override suspend fun handle(request: ByteArray): ByteArray = byteArrayOf(Ctap2Status.INVALID_COMMAND.code.toByte())
}
