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

private const val LABEL_COMPACT_VIEW = "Compact view"
private const val LABEL_PREFER_RP_NAME = "Prefer RP name over ID"
private const val DESCRIPTION_PREFER_RP_NAME = "The ID always determines trust."

@Composable
private fun CompactViewRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        trailingContent = {
            Switch(
                checked = uiState.settings.compactView,
                onCheckedChange = viewModel.settings::setCompactView,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_COMPACT_VIEW)
    }
}

@Composable
private fun PreferRpNameRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        trailingContent = {
            Switch(
                checked = uiState.settings.preferRpName,
                onCheckedChange = viewModel.settings::setPreferRpName,
            )
        },
        supportingContent = { Text(DESCRIPTION_PREFER_RP_NAME, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_PREFER_RP_NAME)
    }
}

@Composable
internal fun InterfaceSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
) {
    Section(title = SECTION_TITLE_INTERFACE) {
        item { shape -> CompactViewRow(uiState, viewModel, shape) }
        item { shape -> PreferRpNameRow(uiState, viewModel, shape) }
    }
}
