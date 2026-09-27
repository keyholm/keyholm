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

private const val PEER_ADDRESS = "00:11:22:33:44:55"

@RunWith(RobolectricTestRunner::class)
class L2capServerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

    private class Peer(
        val server: L2capServer,
        val socket: BluetoothSocket,
    ) {
        fun write(text: String) = shadowOf(socket).inputStreamFeeder.write(text.toByteArray())

        fun hangUp() = shadowOf(socket).inputStreamFeeder.close()

        fun read(count: Int): String = String(ByteArray(count).also { DataInputStream(shadowOf(socket).outputStreamSink).readFully(it) })
    }

    private fun connectPeer(): Peer {
        val serverSocket = adapter.listenUsingInsecureL2capChannel()
        val socket = shadowOf(serverSocket).deviceConnected(adapter.getRemoteDevice(PEER_ADDRESS))
        return Peer(L2capServer(serverSocket, Dispatchers.IO), socket)
    }

    private suspend fun Peer.accept(): L2capConnection = server.accept {}.getOrThrow()

    private fun Result<ByteArray?>.text(): String? = getOrThrow()?.let(::String)

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
    fun `splits messages on CRLF`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write("first\r\nsecond\r\n")

            assertThat(connection.receive().text()).isEqualTo("first")
            assertThat(connection.receive().text()).isEqualTo("second")
        }

    @Test
    fun `joins a message split across writes`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            val message = async(Dispatchers.IO) { connection.receive().text() }
            peer.write("fir")
            peer.write("st\r\n")

            assertThat(message.await()).isEqualTo("first")
        }

    @Test
    fun `finds a CRLF split across writes`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            val first = async(Dispatchers.IO) { connection.receive().text() }
            peer.write("first\r")
            peer.write("\nsecond\r\n")

            assertThat(first.await()).isEqualTo("first")
            assertThat(connection.receive().text()).isEqualTo("second")
        }

    @Test
    fun `receives a message longer than the pipe buffer`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()
            val long = "x".repeat(3000)

            val message = async(Dispatchers.IO) { connection.receive().text() }
            peer.write("$long\r\n")

            assertThat(message.await()).isEqualTo(long)
        }

    @Test
    fun `returns null once the peer hangs up`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            peer.write("complete\r\nincomplete")
            peer.hangUp()

            assertThat(connection.receive().text()).isEqualTo("complete")
            assertThat(connection.receive().text()).isNull()
        }

    @Test
    fun `terminates sent messages with CRLF`() =
        runBlocking<Unit> {
            val peer = connectPeer()
            val connection = peer.accept()

            connection.send("hello".toByteArray()).getOrThrow()

            assertThat(peer.read("hello\r\n".length)).isEqualTo("hello\r\n")
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
