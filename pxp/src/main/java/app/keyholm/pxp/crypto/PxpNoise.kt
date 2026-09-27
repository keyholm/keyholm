package app.keyholm.pxp.crypto

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal object PxpNative {
    init {
        System.loadLibrary("pxp_crypto")
    }

    external fun createAdvert(
        secret: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    external fun open(
        secret: ByteArray,
        advertPlaintext: ByteArray,
        clientPublicKey: ByteArray,
        handshake: ByteArray,
    ): Long

    external fun handshakeReply(handle: Long): ByteArray

    external fun encrypt(
        handle: Long,
        plaintext: ByteArray,
    ): ByteArray

    external fun decrypt(
        handle: Long,
        frame: ByteArray,
    ): ByteArray

    external fun close(handle: Long)
}

interface PxpSession : Closeable {
    val handshakeReply: ByteArray

    fun encrypt(plaintext: ByteArray): Result<ByteArray>

    fun decrypt(frame: ByteArray): Result<ByteArray>
}

interface PxpCrypto {
    fun createAdvert(
        secret: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    fun respond(
        qrSecret: ByteArray,
        advertPlaintext: ByteArray,
        clientPublicKey: ByteArray,
        handshake: ByteArray,
    ): Result<PxpSession>
}

class PxpNoise private constructor(
    private val handle: Long,
    override val handshakeReply: ByteArray,
) : PxpSession {
    private val closed = AtomicBoolean(false)

    override fun encrypt(plaintext: ByteArray): Result<ByteArray> =
        runCatching {
            check(!closed.get()) { "session is closed" }
            PxpNative.encrypt(handle, plaintext)
        }

    override fun decrypt(frame: ByteArray): Result<ByteArray> =
        runCatching {
            check(!closed.get()) { "session is closed" }
            PxpNative.decrypt(handle, frame)
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) PxpNative.close(handle)
    }

    companion object : PxpCrypto {
        override fun createAdvert(
            secret: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = PxpNative.createAdvert(secret, plaintext)

        override fun respond(
            qrSecret: ByteArray,
            advertPlaintext: ByteArray,
            clientPublicKey: ByteArray,
            handshake: ByteArray,
        ): Result<PxpSession> {
            val handle =
                runCatching { PxpNative.open(qrSecret, advertPlaintext, clientPublicKey, handshake) }
                    .getOrElse { return Result.failure(it) }
            return runCatching { PxpNoise(handle, PxpNative.handshakeReply(handle)) }
                .onFailure { PxpNative.close(handle) }
        }
    }
}
