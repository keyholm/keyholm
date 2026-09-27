package app.keyholm.pxp.crypto

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private var handle: Long,
    override val handshakeReply: ByteArray,
) : PxpSession {
    private val lock = ReentrantLock()

    override fun encrypt(plaintext: ByteArray): Result<ByteArray> =
        runCatching {
            lock.withLock {
                check(handle != 0L) { "session is closed" }
                PxpNative.encrypt(handle, plaintext)
            }
        }

    override fun decrypt(frame: ByteArray): Result<ByteArray> =
        runCatching {
            lock.withLock {
                check(handle != 0L) { "session is closed" }
                PxpNative.decrypt(handle, frame)
            }
        }

    override fun close() {
        lock.withLock {
            if (handle != 0L) {
                PxpNative.close(handle)
                handle = 0
            }
        }
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
