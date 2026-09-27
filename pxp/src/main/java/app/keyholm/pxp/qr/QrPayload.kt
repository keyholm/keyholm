package app.keyholm.pxp.qr

import androidx.annotation.VisibleForTesting
import app.keyholm.pxp.CANONICAL_CBOR
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.io.ByteArrayOutputStream

private const val QR_SCHEME = "FIDO:/"

private const val CHUNK_DIGITS = 17
private const val CHUNK_BYTES = 7
private val REMAINDER_DIGITS = intArrayOf(0, 3, 5, 8, 10, 13, 15)

private const val KEY_PUBLIC_KEY = 0
private const val KEY_SECRET = 1
private const val KEY_TUNNEL_DOMAINS = 2
private const val KEY_HINT = 5
private const val KEY_CHANNELS = 6
private const val KEY_CONFIRMATION_CODE = -21

private const val PUBLIC_KEY_BYTES = 33
private const val SECRET_BYTES = 16
private const val POINT_EVEN = 0x02
private const val POINT_ODD = 0x03

private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xffL

enum class PxpFlowHint(
    val token: String,
) {
    GET_ASSERTION("ga"),
    MAKE_CREDENTIAL("mc"),
    PRESENT_CREDENTIAL("dcp"),
    ISSUE_CREDENTIAL("dci"),
    ;

    companion object {
        fun fromToken(token: String): PxpFlowHint? = entries.firstOrNull { it.token == token }
    }
}

enum class PxpChannel(
    val id: Int,
) {
    WEBSOCKET(0),
    BLE(1),
    ;

    companion object {
        fun fromId(id: Int): PxpChannel? = entries.firstOrNull { it.id == id }
    }
}

class PxpQrPayload(
    val clientPublicKey: ByteArray,
    val qrSecret: ByteArray,
    val hint: PxpFlowHint,
    val channels: Set<PxpChannel>,
    val confirmationCodeSupported: Boolean,
) {
    companion object {
        fun decode(uri: String): Result<PxpQrPayload> {
            if (!uri.startsWith(QR_SCHEME, ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("not a FIDO URI"))
            }
            val digits = uri.substring(QR_SCHEME.length)
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) {
                return Result.failure(IllegalArgumentException("payload is not decimal digits"))
            }
            val bytes = digitDecode(digits).getOrElse { return Result.failure(it) }
            return fromCbor(bytes)
        }
    }
}

private class Chunk(
    val offset: Int,
    val digitCount: Int,
    val byteCount: Int,
)

private fun chunkLayout(length: Int): List<Chunk>? {
    val full = (0 until length / CHUNK_DIGITS).map { Chunk(it * CHUNK_DIGITS, CHUNK_DIGITS, CHUNK_BYTES) }
    val trailing = length % CHUNK_DIGITS
    if (trailing == 0) return full
    val byteCount = REMAINDER_DIGITS.indexOf(trailing)
    return if (byteCount > 0) full + Chunk(length - trailing, trailing, byteCount) else null
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun digitDecode(digits: String): Result<ByteArray> {
    val layout =
        chunkLayout(digits.length)
            ?: return Result.failure(IllegalArgumentException("${digits.length} digits is not a valid encoded length"))
    val out = ByteArrayOutputStream()
    val failure =
        layout.firstNotNullOfOrNull { chunk ->
            littleEndianBytes(digits, chunk).onSuccess { out.write(it) }.exceptionOrNull()
        }
    return failure?.let { Result.failure(it) } ?: Result.success(out.toByteArray())
}

private fun littleEndianBytes(
    digits: String,
    chunk: Chunk,
): Result<ByteArray> {
    val value =
        digits.substring(chunk.offset, chunk.offset + chunk.digitCount).toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("chunk at ${chunk.offset} is not a number"))
    if (value >= (1L shl (chunk.byteCount * BITS_PER_BYTE))) {
        return Result.failure(IllegalArgumentException("chunk at ${chunk.offset} overflows ${chunk.byteCount} bytes"))
    }
    val bytes = ByteArray(chunk.byteCount)
    var remaining = value
    for (index in 0 until chunk.byteCount) {
        bytes[index] = (remaining and BYTE_MASK).toByte()
        remaining = remaining ushr BITS_PER_BYTE
    }
    return Result.success(bytes)
}

private fun requiredByteString(
    map: CBORObject,
    key: Int,
    size: Int,
    what: String,
): Result<ByteArray> {
    val value =
        map.GetOrDefault(key, null)
            ?: return Result.failure(IllegalArgumentException("missing $what"))
    if (value.type != CBORType.ByteString) {
        return Result.failure(IllegalArgumentException("$what is not a byte string"))
    }
    val bytes = value.GetByteString()
    if (bytes.size != size) {
        return Result.failure(IllegalArgumentException("$what is ${bytes.size} bytes, expected $size"))
    }
    return Result.success(bytes)
}

private fun decodeChannels(map: CBORObject): Set<PxpChannel> {
    val value = map.GetOrDefault(KEY_CHANNELS, null)
    if (value == null || value.type != CBORType.Array) return setOf(PxpChannel.WEBSOCKET)
    return value.values
        .filter { it.type == CBORType.Integer && it.CanValueFitInInt32() }
        .mapNotNull { PxpChannel.fromId(it.AsInt32Value()) }
        .toSet()
}

private fun fromCbor(bytes: ByteArray): Result<PxpQrPayload> {
    val map = runCatching { CBORObject.DecodeFromBytes(bytes, CANONICAL_CBOR) }.getOrElse { return Result.failure(it) }
    if (map.type != CBORType.Map) {
        return Result.failure(IllegalArgumentException("payload is not a CBOR map"))
    }

    val publicKey =
        requiredByteString(map, KEY_PUBLIC_KEY, PUBLIC_KEY_BYTES, "client public key")
            .getOrElse { return Result.failure(it) }
    if (publicKey[0].toInt() != POINT_EVEN && publicKey[0].toInt() != POINT_ODD) {
        return Result.failure(IllegalArgumentException("client public key is not a compressed point"))
    }
    val secret =
        requiredByteString(map, KEY_SECRET, SECRET_BYTES, "QR secret")
            .getOrElse { return Result.failure(it) }

    val tunnelDomains = map.GetOrDefault(KEY_TUNNEL_DOMAINS, null)
    if (tunnelDomains == null || tunnelDomains.type != CBORType.Integer) {
        return Result.failure(IllegalArgumentException("missing tunnel domain count"))
    }

    val hint = map.GetOrDefault(KEY_HINT, null)

    return Result.success(
        PxpQrPayload(
            clientPublicKey = publicKey,
            qrSecret = secret,
            hint =
                hint?.takeIf { it.type == CBORType.TextString }?.let { PxpFlowHint.fromToken(it.AsString()) }
                    ?: PxpFlowHint.GET_ASSERTION,
            channels = decodeChannels(map),
            confirmationCodeSupported = map.GetOrDefault(KEY_CONFIRMATION_CODE, CBORObject.False).isTrue,
        ),
    )
}
