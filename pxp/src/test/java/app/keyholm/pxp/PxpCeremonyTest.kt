package app.keyholm.pxp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.keyholm.pxp.crypto.PxpCrypto
import app.keyholm.pxp.crypto.PxpSession
import app.keyholm.pxp.ctap.PxpAuthenticator
import app.keyholm.pxp.ctap.postHandshakeMessage
import app.keyholm.pxp.qr.PxpChannel
import app.keyholm.pxp.qr.PxpFlowHint
import app.keyholm.pxp.qr.PxpQrPayload
import app.keyholm.pxp.shadows.ClosableBluetoothServerSocket
import app.keyholm.pxp.shadows.RecordingBluetoothAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.source
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PEER_ADDRESS = "00:11:22:33:44:55"
private const val STRANGER_ADDRESS = "66:77:88:99:AA:BB"
private val GET_INFO = "info".toByteArray()
private val CRLF = "\r\n".encodeUtf8()

private val HANDSHAKE = "hello".toByteArray()
private val HANDSHAKE_REPLY = "welcome".toByteArray()

private const val TUNNEL_CTAP: Byte = 1
private const val CTAP_GET_INFO: Byte = 4
private val CTAP_RESPONSE = byteArrayOf(0, 0xa0.toByte())

private class FakeSession : PxpSession {
    override val handshakeReply = HANDSHAKE_REPLY

    override fun encrypt(plaintext: ByteArray) = Result.success(plaintext)

    override fun decrypt(frame: ByteArray) = Result.success(frame)

    override fun close() = Unit
}

private object FakeCrypto : PxpCrypto {
    override fun createAdvert(
        secret: ByteArray,
        plaintext: ByteArray,
    ) = plaintext

    override fun respond(
        qrSecret: ByteArray,
        advertPlaintext: ByteArray,
        clientPublicKey: ByteArray,
        handshake: ByteArray,
    ): Result<PxpSession> =
        if (handshake.contentEquals(HANDSHAKE)) {
            Result.success(FakeSession())
        } else {
            Result.failure(IllegalArgumentException("unexpected handshake"))
        }
}

private class FakeAuthenticator : PxpAuthenticator {
    override suspend fun getInfo() = GET_INFO

    val requests = mutableListOf<ByteArray>()

    override suspend fun handle(request: ByteArray): ByteArray {
        requests.add(request)
        return CTAP_RESPONSE
    }
}

private class Peer(
    val socket: BluetoothSocket,
) {
    private val feeder = shadowOf(socket).inputStreamFeeder
    private val sink = shadowOf(socket).outputStreamSink.source().buffer()

    fun send(message: ByteArray) = feeder.write(message + CRLF.toByteArray())

    fun receive(): ByteArray = sink.readByteArray(sink.indexOf(CRLF)).also { sink.skip(CRLF.size.toLong()) }

    fun hangUp() = feeder.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordingBluetoothAdapter::class, ClosableBluetoothServerSocket::class])
class PxpCeremonyTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val authenticator = FakeAuthenticator()

    private val payload =
        PxpQrPayload(
            clientPublicKey = ByteArray(33),
            qrSecret = ByteArray(16),
            hint = PxpFlowHint.GET_ASSERTION,
            channels = setOf(PxpChannel.BLE),
            confirmationCodeSupported = false,
        )

    // Advertising starts once the ceremony is accepting.
    private suspend fun awaitAdvertising() =
        withTimeout(5.seconds) {
            val advertiser = shadowOf(adapter.bluetoothLeAdvertiser)
            while (advertiser.lastAdvertisingData == null) delay(10.milliseconds)
        }

    private fun connect(address: String): Peer {
        val server = checkNotNull(Shadow.extract<RecordingBluetoothAdapter>(adapter).l2capServerSocket)
        return Peer(shadowOf(server).deviceConnected(adapter.getRemoteDevice(address)))
    }

    private fun Peer.handshake() {
        send(HANDSHAKE)
        assertThat(receive()).isEqualTo(HANDSHAKE_REPLY)
        assertThat(receive()).isEqualTo(postHandshakeMessage(GET_INFO))
    }

    @Test
    fun `answers ctap requests over the opened channel`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(true)
            val ceremony = PxpCeremony(context, payload, authenticator, FakeCrypto)
            val running = launch(Dispatchers.Default) { ceremony.run() }
            awaitAdvertising()

            val peer = connect(PEER_ADDRESS)
            peer.handshake()

            assertThat(ceremony.status.value.stage).isEqualTo(PxpStage.ChannelOpen)

            peer.send(byteArrayOf(TUNNEL_CTAP, CTAP_GET_INFO))

            assertThat(peer.receive()).isEqualTo(byteArrayOf(TUNNEL_CTAP) + CTAP_RESPONSE)
            assertThat(authenticator.requests.single()).isEqualTo(byteArrayOf(CTAP_GET_INFO))

            peer.hangUp()
            withTimeout(5.seconds) { running.join() }

            assertThat(ceremony.status.value).isEqualTo(PxpStatus(PxpStage.ChannelClosed, failure = null))
        }

    @Test
    fun `keeps accepting after a failed handshake`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(true)
            val ceremony = PxpCeremony(context, payload, authenticator, FakeCrypto)
            val running = launch(Dispatchers.Default) { ceremony.run() }
            awaitAdvertising()

            val stranger = connect(STRANGER_ADDRESS)
            withTimeout(5.seconds) { while (!stranger.socket.isConnected) delay(10.milliseconds) }
            stranger.send("junk".toByteArray())
            withTimeout(5.seconds) { while (stranger.socket.isConnected) delay(10.milliseconds) }

            val peer = connect(PEER_ADDRESS)
            peer.handshake()

            peer.hangUp()
            withTimeout(5.seconds) { running.join() }

            assertThat(ceremony.status.value).isEqualTo(PxpStatus(PxpStage.ChannelClosed, failure = null))
        }
}
