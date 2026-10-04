package app.keyholm.ui.main.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel

private const val LABEL_PXP = "Scan QR code to login"
private const val DESCRIPTION_PXP = "Support using Keyholm with a different device by scanning a QR code with your camera app."

@Composable
private fun PxpRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            Text(DESCRIPTION_PXP, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = uiState.device.pxpEnabled,
                onCheckedChange = viewModel::setPxpEnabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_PXP)
    }
}

@Composable
internal fun ExperimentalSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
) {
    Section(title = SECTION_TITLE_EXPERIMENTAL) {
        item { shape -> PxpRow(uiState, viewModel, shape) }
    }
}
