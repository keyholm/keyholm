package app.keyholm.ctap

import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

internal val CANONICAL_CBOR = CBOREncodeOptions("ctap2canonical=true")

internal fun CBORObject.byteStringOrNull(key: Any): ByteArray? =
    GetOrDefault(key, null)?.takeIf { it.type == CBORType.ByteString }?.GetByteString()

internal fun CBORObject.textOrNull(key: Any): String? = GetOrDefault(key, null)?.takeIf { it.type == CBORType.TextString }?.AsString()
