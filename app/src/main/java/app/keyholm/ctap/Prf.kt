package app.keyholm.ctap

@JvmInline
value class PrfOutput(
    val bytes: ByteArray,
)

class PrfSalts(
    val first: ByteArray,
    val second: ByteArray?,
)

data class PrfResults(
    val first: PrfOutput,
    val second: PrfOutput?,
)
