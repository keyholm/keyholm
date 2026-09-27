package app.keyholm.ui.pxp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.PxpStatus
import app.keyholm.ui.theme.titleColor

private enum class PxpStep(
    val label: String,
) {
    Connect("Establishing proximity…"),
    Secure("Opening channel…"),
    Serve("Handling requests…"),
}

private val PxpStage.completedSteps: Int
    get() =
        when (this) {
            PxpStage.EstablishingProximity -> 0
            PxpStage.ClientConnected -> 1
            PxpStage.ChannelOpen -> 2
            PxpStage.ChannelClosed -> 3
        }

@Composable
internal fun PxpStatusScreen(
    status: PxpStatus,
    confirmationCode: String?,
    onClose: () -> Unit,
) {
    val (stage, failure) = status
    val completed = stage.completedSteps
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp)) {
            confirmationCode?.let { code ->
                Text(
                    code.chunked(CODE_GROUP).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier =
                        Modifier
                            .align(CODE_ALIGNMENT)
                            .alpha(if (stage >= PxpStage.ChannelOpen) 1f else 0f),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatusIndicator(stage, failure)
                StepList(completed, failure)
            }
            if (failure != null || stage == PxpStage.ChannelClosed) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    failure?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusIndicator(
    stage: PxpStage,
    failure: String?,
) {
    when {
        failure != null -> {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(INDICATOR_SIZE),
            )
        }

        // Held still once the channel has closed.
        stage == PxpStage.ChannelClosed -> {
            LoadingIndicator(progress = { 1f }, modifier = Modifier.size(INDICATOR_SIZE), color = titleColor)
        }

        else -> {
            LoadingIndicator(Modifier.size(INDICATOR_SIZE), color = titleColor)
        }
    }
}

@Composable
private fun StepList(
    completed: Int,
    failure: String?,
) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PxpStep.entries.forEachIndexed { index, step ->
            val state =
                when {
                    index < completed -> StepState.Done
                    failure != null -> StepState.Failed
                    else -> StepState.Ongoing
                }
            StepRow(step.label, state, Modifier.alpha(if (index > completed) 0f else 1f))
        }
    }
}

private val INDICATOR_SIZE = 96.dp
private const val CODE_GROUP = 3

// A quarter of the way down, clear of the decrypt prompt that covers the lower screen.
private val CODE_ALIGNMENT = BiasAlignment(horizontalBias = 0f, verticalBias = -0.5f)

private enum class StepState { Ongoing, Done, Failed }

private val STEP_ICON_SIZE = 24.dp
private val STEP_ICON_GAP = 16.dp

@Composable
private fun StepRow(
    text: String,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) =
            when (state) {
                StepState.Ongoing -> Icons.Default.Pending to MaterialTheme.colorScheme.primary
                StepState.Done -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                StepState.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
            }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(STEP_ICON_SIZE))
        Spacer(Modifier.width(STEP_ICON_GAP))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
