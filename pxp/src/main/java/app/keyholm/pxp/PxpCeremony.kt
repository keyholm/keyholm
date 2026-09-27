package app.keyholm.pxp

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import app.keyholm.pxp.ble.AdvertisingState
import app.keyholm.pxp.ble.L2capConnection
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
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
    ClientShutdown,
}

data class PxpStatus(
    val stage: PxpStage,
    val failure: PxpFailure?,
)

private sealed interface Opening {
    class Opened(
        val channel: NoiseChannel,
    ) : Opening

    class Failed(
        val failure: PxpFailure,
    ) : Opening
}

private sealed interface TunnelResult {
    class Reply(
        val message: ByteArray,
    ) : TunnelResult

    data object Shutdown : TunnelResult

    data object Ignore : TunnelResult

    class ProtocolError(
        val failure: PxpFailure,
    ) : TunnelResult
}

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
    private fun fail(failure: PxpFailure) {
        log.w { "failed: $failure" }
        _status.update { if (it.failure == null) it.copy(failure = failure) else it }
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT],
    )
    suspend fun run() {
        withL2capServer(context, onFailure = { fail(PxpFailure.Listening(it)) }) { listen(it) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private suspend fun listen(server: L2capServer) {
        log.i { "listening, psm ${server.psm}" }
        val nonce = ByteArray(PxpAdvertisement.NONCE_BYTES).also(SecureRandom()::nextBytes)
        val advertisement = PxpAdvertisement.create(payload.qrSecret, nonce, server.psm, crypto::createAdvert)
        val channel =
            when (val opening = open(server, advertisement)) {
                is Opening.Failed -> {
                    fail(opening.failure)
                    return
                }

                is Opening.Opened -> {
                    opening.channel
                }
            }
        _status.update { it.copy(stage = PxpStage.ClientConnected) }
        channel.use { exchange(it) }
    }

    // Accepts until a connection completes the Noise handshake.
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private suspend fun open(
        server: L2capServer,
        advertisement: PxpAdvertisement,
    ): Opening =
        coroutineScope {
            val accepting = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Opening>()
            launch {
                accepting.await()
                advertise(context, advertisement.serviceData).collect { state ->
                    log.d { "advertising: $state" }
                    if (state is AdvertisingState.Failed) opened.complete(Opening.Failed(PxpFailure.Advertising(state.failure)))
                }
            }
            launch {
                while (true) {
                    val connection =
                        server.accept { accepting.complete(Unit) }.getOrElse {
                            opened.complete(Opening.Failed(PxpFailure.Accepting(it)))
                            return@launch
                        }
                    launch {
                        val channel = handshake(connection, advertisement)
                        if (channel == null || !opened.complete(Opening.Opened(channel))) {
                            channel?.close()
                            connection.close()
                        }
                    }
                }
            }
            withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT) { opened.await() }
            opened.complete(Opening.Failed(PxpFailure.Timeout(CHANNEL_OPEN_TIMEOUT)))
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
                .onFailure { log.w { "no handshake from the peer" } }
                .getOrNull() ?: return null
        return crypto
            .respond(payload.qrSecret, advertisement.plaintext, payload.clientPublicKey, message)
            .onFailure { log.w { "handshake failed" } }
            .getOrNull()
            ?.let { NoiseChannel(connection, it) }
    }

    private suspend fun exchange(channel: NoiseChannel) {
        log.i { "handshake complete" }
        var open =
            channel
                .sendHandshakeReply()
                .onFailure { fail(PxpFailure.Sending(it)) }
                .onSuccess { _status.update { status -> status.copy(stage = PxpStage.ChannelOpen) } }
                .isSuccess && send(channel, postHandshakeMessage(authenticator.getInfo())).isSuccess
        while (open) {
            val message = channel.receive().onFailure { fail(PxpFailure.Receiving(it)) }.getOrNull()
            open =
                when (val result = message?.let { onTunnelMessage(it) }) {
                    null -> {
                        false
                    }

                    is TunnelResult.Reply -> {
                        send(channel, result.message).isSuccess
                    }

                    TunnelResult.Ignore -> {
                        true
                    }

                    TunnelResult.Shutdown -> {
                        _status.update { it.copy(stage = PxpStage.ClientShutdown) }
                        false
                    }

                    is TunnelResult.ProtocolError -> {
                        fail(result.failure)
                        false
                    }
                }
        }
        if (_status.value.failure == null && _status.value.stage == PxpStage.ChannelOpen) {
            log.i { "channel ended" }
            _status.update { it.copy(stage = PxpStage.ChannelClosed) }
        }
    }

    private suspend fun onTunnelMessage(message: ByteArray): TunnelResult {
        if (message.isEmpty()) return TunnelResult.ProtocolError(PxpFailure.EmptyTunnelMessage)
        return when (val type = message.first().toInt()) {
            TUNNEL_SHUTDOWN -> {
                log.i { "peer sent shutdown" }
                TunnelResult.Shutdown
            }

            TUNNEL_CTAP -> {
                onCtap(message.copyOfRange(1, message.size))
            }

            TUNNEL_UPDATE -> {
                log.d { "ignoring an update message" }
                TunnelResult.Ignore
            }

            else -> {
                log.d { "ignoring unknown tunnel message type $type" }
                TunnelResult.Ignore
            }
        }
    }

    private suspend fun onCtap(request: ByteArray): TunnelResult {
        if (request.isEmpty()) return TunnelResult.ProtocolError(PxpFailure.EmptyCtapMessage)
        return TunnelResult.Reply(byteArrayOf(TUNNEL_CTAP.toByte()) + authenticator.handle(request))
    }

    private suspend fun send(
        channel: NoiseChannel,
        plaintext: ByteArray,
    ): Result<Unit> = channel.send(plaintext).onFailure { fail(PxpFailure.Sending(it)) }
}
