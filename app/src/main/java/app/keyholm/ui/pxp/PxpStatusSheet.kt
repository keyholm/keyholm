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
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.PxpStatus
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
                    failure,
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
private fun StatusIndicator(failure: String?) {
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
