package app.keyholm.pxp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.DataInputStream
import java.nio.ByteBuffer

private const val PEER_ADDRESS = "00:11:22:33:44:55"

private val FIRST = byteArrayOf(1, 2, 3)
private val SECOND = byteArrayOf(4, 5)

private fun framed(message: ByteArray): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(message.size).array() + message

@RunWith(RobolectricTestRunner::class)
class L2capServerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

    private class Peer(
        val server: L2capServer,
        val socket: BluetoothSocket,
    ) {
        fun write(bytes: ByteArray) = shadowOf(socket).inputStreamFeeder.write(bytes)

        fun hangUp() = shadowOf(socket).inputStreamFeeder.close()

        fun read(count: Int): ByteArray = ByteArray(count).also { DataInputStream(shadowOf(socket).outputStreamSink).readFully(it) }
    }

    private fun connectPeer(): Peer {
        val serverSocket = adapter.listenUsingInsecureL2capChannel()
        val socket = shadowOf(serverSocket).deviceConnected(adapter.getRemoteDevice(PEER_ADDRESS))
        return Peer(L2capServer(serverSocket, Dispatchers.IO), socket)
    }

    private suspend fun Peer.accept(): L2capConnection = server.accept {}.getOrThrow()

    @Test
    fun `fails when bluetooth is off`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(false)

            val result = withL2capServer(context) {}

            assertThat(result.exceptionOrNull()).isInstanceOf(L2capFailure::class.java)
        }

    @Test
    fun `returns the block result`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(true)

            assertThat(withL2capServer(context) { "done" }.getOrThrow()).isEqualTo("done")
        }

    @Test
    fun `runs onAccepting before accepting`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            var accepting = false

            peer.server.accept { accepting = true }.getOrThrow()

            assertThat(accepting).isTrue()
        }

    @Test
    fun `splits messages on their length prefix`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write(framed(FIRST) + framed(SECOND))

            assertThat(connection.receive().getOrThrow()).isEqualTo(FIRST)
            assertThat(connection.receive().getOrThrow()).isEqualTo(SECOND)
        }

    @Test
    fun `joins a message split across writes`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()
            val bytes = framed(FIRST)

            val message = async(Dispatchers.IO) { connection.receive().getOrThrow() }
            peer.write(bytes.copyOfRange(0, 6))
            peer.write(bytes.copyOfRange(6, bytes.size))

            assertThat(message.await()).isEqualTo(FIRST)
        }

    @Test
    fun `joins a length prefix split across writes`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()
            val bytes = framed(FIRST)

            val message = async(Dispatchers.IO) { connection.receive().getOrThrow() }
            peer.write(bytes.copyOfRange(0, 2))
            peer.write(bytes.copyOfRange(2, bytes.size))

            assertThat(message.await()).isEqualTo(FIRST)
        }

    @Test
    fun `receives a message longer than the pipe buffer`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()
            val long = ByteArray(3000) { it.toByte() }

            val message = async(Dispatchers.IO) { connection.receive().getOrThrow() }
            peer.write(framed(long))

            assertThat(message.await()).isEqualTo(long)
        }

    @Test
    fun `rejects a message longer than a Noise frame`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(65_536).array())

            assertThat(connection.receive().isFailure).isTrue()
        }

    @Test
    fun `returns null once the peer hangs up`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write(framed(FIRST))
            peer.hangUp()

            assertThat(connection.receive().getOrThrow()).isEqualTo(FIRST)
            assertThat(connection.receive().getOrThrow()).isNull()
        }

    @Test
    fun `fails on a message cut off by a hang-up`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write(framed(FIRST).copyOfRange(0, 6))
            peer.hangUp()

            assertThat(connection.receive().isFailure).isTrue()
        }

    @Test
    fun `prefixes sent messages with their length`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            connection.send(FIRST).getOrThrow()

            assertThat(peer.read(framed(FIRST).size)).isEqualTo(framed(FIRST))
        }

    @Test
    fun `closing the server closes accepted channels`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            peer.accept()

            peer.server.close()

            assertThat(peer.socket.isConnected).isFalse()
            assertThat(peer.server.accept {}.isFailure).isTrue()
        }
}
