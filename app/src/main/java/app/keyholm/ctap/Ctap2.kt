package app.keyholm.ctap

import com.upokecenter.cbor.CBORObject

// Command bytes
internal const val CTAP_MAKE_CREDENTIAL = 0x01
internal const val CTAP_GET_ASSERTION = 0x02
internal const val CTAP_GET_INFO = 0x04
internal const val CTAP_GET_NEXT_ASSERTION = 0x08

internal const val PUBLIC_KEY_TYPE = "public-key"

// Status codes
private const val CODE_OK = 0x00
private const val CODE_INVALID_COMMAND = 0x01
private const val CODE_INVALID_PARAMETER = 0x02
private const val CODE_CBOR_UNEXPECTED_TYPE = 0x11
private const val CODE_INVALID_CBOR = 0x12
private const val CODE_MISSING_PARAMETER = 0x14
private const val CODE_CREDENTIAL_EXCLUDED = 0x19
private const val CODE_UNSUPPORTED_ALGORITHM = 0x26
private const val CODE_OPERATION_DENIED = 0x27
private const val CODE_UNSUPPORTED_OPTION = 0x2B
private const val CODE_INVALID_OPTION = 0x2C
private const val CODE_NO_CREDENTIALS = 0x2E
private const val CODE_NOT_ALLOWED = 0x30
private const val CODE_OTHER = 0x7F

internal enum class Ctap2Status(
    val code: Int,
) {
    OK(CODE_OK),
    INVALID_COMMAND(CODE_INVALID_COMMAND),
    INVALID_PARAMETER(CODE_INVALID_PARAMETER),
    CBOR_UNEXPECTED_TYPE(CODE_CBOR_UNEXPECTED_TYPE),
    INVALID_CBOR(CODE_INVALID_CBOR),
    MISSING_PARAMETER(CODE_MISSING_PARAMETER),
    CREDENTIAL_EXCLUDED(CODE_CREDENTIAL_EXCLUDED),
    UNSUPPORTED_ALGORITHM(CODE_UNSUPPORTED_ALGORITHM),
    OPERATION_DENIED(CODE_OPERATION_DENIED),
    UNSUPPORTED_OPTION(CODE_UNSUPPORTED_OPTION),
    INVALID_OPTION(CODE_INVALID_OPTION),
    NO_CREDENTIALS(CODE_NO_CREDENTIALS),
    NOT_ALLOWED(CODE_NOT_ALLOWED),
    OTHER(CODE_OTHER),
}

internal class Ctap2Response(
    val status: Ctap2Status,
    private val body: ByteArray = ByteArray(0),
) {
    constructor(body: CBORObject) : this(Ctap2Status.OK, body.EncodeToBytes(CANONICAL_CBOR))

    fun encode(): ByteArray = byteArrayOf(status.code.toByte()) + body
}

internal sealed interface Ctap2Result<out T> {
    data class Ok<T>(
        val value: T,
    ) : Ctap2Result<T>

    data class Rejected(
        val status: Ctap2Status,
    ) : Ctap2Result<Nothing>
}

internal inline fun <T, R> Ctap2Result<T>.then(next: (T) -> Ctap2Result<R>): Ctap2Result<R> =
    when (this) {
        is Ctap2Result.Ok -> next(value)
        is Ctap2Result.Rejected -> this
    }
