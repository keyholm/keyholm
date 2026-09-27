package app.keyholm.ctap

// Status codes, CTAP 2.3 6.3.
private const val CODE_INVALID_COMMAND = 0x01

enum class Ctap2Status(
    val code: Int,
) {
    INVALID_COMMAND(CODE_INVALID_COMMAND),
}
