package app.keyholm.pxp.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

private const val MAX_MESSAGE_BYTES = 65_535L

class L2capFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun <T> withL2capServer(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend (L2capServer) -> T,
): Result<T> {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null || !adapter.isEnabled) {
        return Result.failure(L2capFailure("bluetooth is unavailable"))
    }
    val server =
        runCatching { L2capServer(adapter.listenUsingInsecureL2capChannel(), dispatcher) }
            .getOrElse { return Result.failure(L2capFailure("could not open an L2CAP channel", it)) }
    return try {
        Result.success(block(server))
    } finally {
        server.close()
    }
}

class L2capServer internal constructor(
    private val socket: BluetoothServerSocket,
    private val dispatcher: CoroutineDispatcher,
) {
    private val accepted = ConcurrentLinkedQueue<BluetoothSocket>()

    val psm: Int get() = socket.psm

    suspend fun accept(onAccepting: () -> Unit): Result<L2capConnection> =
        socket.closingOnCancel(dispatcher) {
            onAccepting()
            runCatching { L2capConnection(socket.accept().also { accepted.add(it) }, dispatcher) }
        }

    internal fun close() {
        accepted.forEach { runCatching { it.close() } }
        runCatching { socket.close() }
    }
}

class L2capConnection internal constructor(
    private val socket: BluetoothSocket,
    private val dispatcher: CoroutineDispatcher,
) {
    private val sending = Mutex()
    private val source = socket.inputStream.source().buffer()
    private val sink = socket.outputStream.sink().buffer()

    suspend fun receive(): Result<ByteArray?> =
        socket.closingOnCancel(dispatcher) {
            runCatching {
                if (source.exhausted()) {
                    null
                } else {
                    val length = source.readInt().toUInt().toLong()
                    if (length > MAX_MESSAGE_BYTES) throw L2capFailure("message exceeds $MAX_MESSAGE_BYTES bytes")
                    source.readByteArray(length)
                }
            }
        }

    suspend fun send(message: ByteArray): Result<Unit> =
        sending.withLock {
            socket.closingOnCancel(dispatcher) {
                runCatching { sink.writeInt(message.size).write(message).flush() }
            }
        }

    internal fun close() {
        runCatching { socket.close() }
    }
}

// Blocking socket calls ignore interrupts, so cancelling closes the socket to unblock them.
private suspend fun <T> Closeable.closingOnCancel(
    dispatcher: CoroutineDispatcher,
    block: () -> T,
): T =
    coroutineScope {
        val call = async(dispatcher) { block() }
        try {
            call.await()
        } catch (e: CancellationException) {
            runCatching { close() }
            throw e
        }
    }
