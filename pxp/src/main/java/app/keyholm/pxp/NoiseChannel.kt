package app.keyholm.pxp

import app.keyholm.pxp.ble.L2capConnection
import app.keyholm.pxp.crypto.PxpSession
import java.io.Closeable

internal class NoiseChannel(
    private val connection: L2capConnection,
    private val session: PxpSession,
) : Closeable {
    suspend fun sendHandshakeReply(): Result<Unit> = connection.send(session.handshakeReply)

    suspend fun send(plaintext: ByteArray): Result<Unit> {
        val frame = session.encrypt(plaintext).getOrElse { return Result.failure(it) }
        return connection.send(frame)
    }

    suspend fun receive(): Result<ByteArray?> {
        val frame = connection.receive().getOrElse { return Result.failure(it) } ?: return Result.success(null)
        return session.decrypt(frame)
    }

    override fun close() {
        session.close()
        connection.close()
    }
}
