package app.keyholm.pxp.qr

import java.nio.ByteBuffer
import java.security.MessageDigest

private const val CODE_MODULUS = 1_000_000L
private const val CODE_DIGITS = 6

// The client calculates the same confirmation code
fun PxpQrPayload.confirmationCode(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(clientPublicKey)
    val value =
        ByteBuffer
            .wrap(digest)
            .int
            .toUInt()
            .toLong() % CODE_MODULUS
    return value.toString().padStart(CODE_DIGITS, '0')
}
