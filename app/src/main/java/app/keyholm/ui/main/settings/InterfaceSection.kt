package app.keyholm.ui.main.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel

private const val LABEL_COMPACT_VIEW = "Compact view"
private const val LABEL_PREFER_RP_NAME = "Prefer RP name over ID"
private const val DESCRIPTION_PREFER_RP_NAME = "The ID always determines trust."
private const val LABEL_ICON_PACK = "Icons"
private const val DESCRIPTION_ICON_PACK =
    "Import an Aegis icon pack and match icons by RP."
private const val CONTENT_DESCRIPTION_REMOVE_ICON_PACK = "Remove icon pack"
private const val MIME_ZIP = "application/zip"

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
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_PREFER_RP_NAME)
    }
}

@Composable
private fun IconPackRow(
    viewModel: MainViewModel,
    shape: Shape,
) {
    val pack by viewModel.iconPacks.pack.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.iconPacks.import(uri)
        }
    val installed = pack
    ListItem(
        selected = false,
        onClick = { picker.launch(arrayOf(MIME_ZIP)) },
        supportingContent = {
            val description = installed?.let { "${it.name} (version ${it.version})" } ?: DESCRIPTION_ICON_PACK
            Text(description, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent =
            if (installed != null) {
                {
                    IconButton(onClick = viewModel.iconPacks::remove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = CONTENT_DESCRIPTION_REMOVE_ICON_PACK,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else {
                null
            },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_ICON_PACK)
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
        item { shape -> IconPackRow(viewModel, shape) }
    }
}
