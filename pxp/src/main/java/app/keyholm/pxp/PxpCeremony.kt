package app.keyholm.pxp

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import app.keyholm.pxp.ble.L2capConnection
import app.keyholm.pxp.ble.L2capFailure
import app.keyholm.pxp.ble.L2capServer
import app.keyholm.pxp.ble.advertise
import app.keyholm.pxp.ble.withL2capServer
import app.keyholm.pxp.crypto.PxpAdvertisement
import app.keyholm.pxp.crypto.PxpCrypto
import app.keyholm.pxp.ctap.PxpAuthenticator
import app.keyholm.pxp.ctap.postHandshakeMessage
import app.keyholm.pxp.qr.PxpQrPayload
import app.keyholm.pxp.qr.confirmationCode
import app.keyholm.pxp.util.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

// Should be more than enough
private val CHANNEL_OPEN_TIMEOUT = 3.minutes

private const val TUNNEL_SHUTDOWN = 0
private const val TUNNEL_CTAP = 1
private const val TUNNEL_UPDATE = 2

enum class PxpStage {
    EstablishingProximity,
    ClientConnected,
    ChannelOpen,
    ChannelClosed,
}

data class PxpStatus(
    val stage: PxpStage,
    val failure: String?,
)

class PxpCeremony(
    private val context: Context,
    private val payload: PxpQrPayload,
    private val authenticator: PxpAuthenticator,
    private val crypto: PxpCrypto,
) {
    private val log = logger()
    private val _status = MutableStateFlow(PxpStatus(PxpStage.EstablishingProximity, failure = null))
    val status: StateFlow<PxpStatus> = _status.asStateFlow()
    val confirmationCode: String? = if (payload.confirmationCodeSupported) payload.confirmationCode() else null

    // Keeps the first failure.
    private fun fail(message: String) {
        log.w { message }
        _status.update { if (it.failure == null) it.copy(failure = message) else it }
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT],
    )
    suspend fun run() {
        withL2capServer(context) { listen(it) }
            .onFailure { fail("l2cap closed: ${it.message}") }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private suspend fun listen(server: L2capServer) {
        log.i { "listening, psm ${server.psm}" }
        val nonce = ByteArray(PxpAdvertisement.NONCE_BYTES).also(SecureRandom()::nextBytes)
        val advertisement = PxpAdvertisement.create(payload.qrSecret, nonce, server.psm, crypto::createAdvert)
        val channel =
            open(server, advertisement).getOrElse {
                fail("channel did not open: ${it.message}")
                return
            }
        _status.update { it.copy(stage = PxpStage.ClientConnected) }
        channel.use { exchange(it) }
    }

    // Accepts until a connection completes the Noise handshake.
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private suspend fun open(
        server: L2capServer,
        advertisement: PxpAdvertisement,
    ): Result<NoiseChannel> =
        coroutineScope {
            val accepting = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Result<NoiseChannel>>()
            launch {
                accepting.await()
                advertise(context, advertisement.serviceData)
                    .catch { opened.complete(Result.failure(it)) }
                    .collect { log.d { "advertising: $it" } }
            }
            launch {
                while (true) {
                    val connection =
                        server.accept { accepting.complete(Unit) }.getOrElse {
                            opened.complete(Result.failure(it))
                            return@launch
                        }
                    launch {
                        val channel = handshake(connection, advertisement)
                        if (channel == null || !opened.complete(Result.success(channel))) {
                            channel?.close()
                            connection.close()
                        }
                    }
                }
            }
            withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT) { opened.await() }
            opened.complete(Result.failure(L2capFailure("timed out after $CHANNEL_OPEN_TIMEOUT")))
            coroutineContext.cancelChildren()
            opened.await()
        }

    private suspend fun handshake(
        connection: L2capConnection,
        advertisement: PxpAdvertisement,
    ): NoiseChannel? {
        log.i { "peer connected" }
        val message =
            connection
                .receive()
                .onFailure { log.w { "no handshake from the peer: ${it.message}" } }
                .getOrNull() ?: return null
        return crypto
            .respond(payload.qrSecret, advertisement.plaintext, payload.clientPublicKey, message)
            .onFailure { log.w { "handshake failed: ${it.message}" } }
            .getOrNull()
            ?.let { NoiseChannel(connection, it) }
    }

    private suspend fun exchange(channel: NoiseChannel) {
        _status.update { it.copy(stage = PxpStage.ChannelOpen) }
        log.i { "handshake complete" }
        var open =
            channel
                .sendHandshakeReply()
                .onFailure { fail("handshake reply not sent: ${it.message}") }
                .isSuccess && send(channel, postHandshakeMessage(authenticator.getInfo())).isSuccess
        while (open) {
            val message = channel.receive().onFailure { fail("channel failed: ${it.message}") }.getOrNull()
            open = message != null && onTunnelMessage(message)?.let { send(channel, it).isSuccess } ?: true
        }
        if (_status.value.failure == null) {
            log.i { "channel ended" }
            _status.update { it.copy(stage = PxpStage.ChannelClosed) }
        }
    }

    private suspend fun onTunnelMessage(message: ByteArray): ByteArray? {
        if (message.isEmpty()) return null
        return when (val type = message.first().toInt()) {
            TUNNEL_SHUTDOWN -> {
                log.i { "peer sent shutdown" }
                null
            }

            TUNNEL_CTAP -> {
                onCtap(message.copyOfRange(1, message.size))
            }

            TUNNEL_UPDATE -> {
                log.d { "ignoring an update message" }
                null
            }

            else -> {
                log.d { "ignoring unknown tunnel message type $type" }
                null
            }
        }
    }

    private suspend fun onCtap(request: ByteArray): ByteArray? {
        if (request.isEmpty()) return null
        val command = request.first().toInt()
        log.d { "ctap 0x%02x".format(Locale.ROOT, command) }
        val frame = byteArrayOf(TUNNEL_CTAP.toByte()) + authenticator.handle(request)
        log.d { "ctap 0x%02x -> %d bytes".format(Locale.ROOT, command, frame.size) }
        return frame
    }

    private suspend fun send(
        channel: NoiseChannel,
        plaintext: ByteArray,
    ): Result<Unit> = channel.send(plaintext).onFailure { fail("send failed: ${it.message}") }
}
