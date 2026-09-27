package app.keyholm.ui.pxp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keyholm.pxp.PxpFailure
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.PxpStatus
import app.keyholm.pxp.ble.AdvertisingFailure
import app.keyholm.pxp.ble.L2capFailure
import app.keyholm.pxp.ble.OversizedMessage
import app.keyholm.ui.common.Sheet
import app.keyholm.ui.common.SheetHeader
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.theme.titleColor

private val PxpStage.label: String
    get() =
        when (this) {
            PxpStage.EstablishingProximity -> "Establishing proximity…"
            PxpStage.ClientConnected -> "Opening channel…"
            PxpStage.ChannelOpen -> "Handling requests…"
            PxpStage.ChannelClosed, PxpStage.ClientShutdown -> "Channel closed"
        }

@Composable
internal fun PxpStatusSheet(
    status: PxpStatus,
    confirmationCode: String?,
    onClose: () -> Unit,
) {
    val (stage, failure) = status
    val icon = rememberAppIcon()
    Sheet(onDismiss = onClose) {
        SheetHeader(icon, stage.label)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            confirmationCode?.let { code ->
                Text(
                    code.chunked(CODE_GROUP).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.alpha(if (stage >= PxpStage.ChannelOpen) 1f else 0f),
                )
            }
            StatusIndicator(failure)
            if (failure != null) {
                Text(
                    failure.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (failure != null) {
            Box(
                Modifier.fillMaxWidth().padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Button(onClick = onClose) { Text("Close") }
            }
        }
        Spacer(Modifier.height(56.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusIndicator(failure: PxpFailure?) {
    when {
        failure != null -> {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(INDICATOR_SIZE),
            )
        }

        else -> {
            LoadingIndicator(Modifier.size(INDICATOR_SIZE), color = titleColor)
        }
    }
}

private val INDICATOR_SIZE = 96.dp
private const val CODE_GROUP = 3

private fun PxpFailure.text(): String =
    when (this) {
        is PxpFailure.PermissionsDenied -> "Permission not granted: ${permissions.joinToString { it.substringAfterLast('.') }}"
        PxpFailure.BluetoothNotEnabled -> "Bluetooth is off."
        is PxpFailure.InvalidQrCode -> "Unable to parse QR code."
        PxpFailure.NoBleChannel -> "Client didn't offer BLE transport."
        is PxpFailure.Listening -> failure.reason()
        is PxpFailure.Advertising -> failure.reason()
        is PxpFailure.Accepting -> "Failed to accept a connection."
        is PxpFailure.Timeout -> "Timed out."
        is PxpFailure.Sending -> "Failed to send frame."
        is PxpFailure.Receiving -> (cause as? OversizedMessage)?.let { "Incoming message exceeds limits." } ?: "Channel failed."
        PxpFailure.EmptyTunnelMessage, PxpFailure.EmptyCtapMessage -> "Invalid empty message."
    }

private fun L2capFailure.reason(): String =
    when (this) {
        L2capFailure.BluetoothUnavailable -> "Bluetooth is unavailable."
        is L2capFailure.ChannelNotOpened -> "Could not open an L2CAP channel."
    }

private fun AdvertisingFailure.reason(): String =
    when (this) {
        AdvertisingFailure.NoAdapter -> "No bluetooth adapter."
        AdvertisingFailure.BluetoothOff -> "Bluetooth is off."
        AdvertisingFailure.NoExtendedAdvertising -> "No extended advertising support."
        AdvertisingFailure.NoAdvertiser -> "No BLE advertiser."
        is AdvertisingFailure.Failed -> "Advertising failed with status $status."
    }
