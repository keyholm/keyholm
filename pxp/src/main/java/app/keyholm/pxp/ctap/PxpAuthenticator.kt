package app.keyholm.pxp.ctap

import app.keyholm.pxp.CANONICAL_CBOR
import com.upokecenter.cbor.CBORObject

interface PxpAuthenticator {
    suspend fun getInfo(): ByteArray

    suspend fun handle(request: ByteArray): ByteArray
}

// Max of what we can fit in a Noise frame.
const val MAX_CTAP_MESSAGE_BYTES = 65_502

private const val INITIAL_INFO = 1

fun postHandshakeMessage(getInfo: ByteArray): ByteArray =
    CBORObject
        .NewMap()
        .Add(INITIAL_INFO, getInfo)
        .EncodeToBytes(CANONICAL_CBOR)
