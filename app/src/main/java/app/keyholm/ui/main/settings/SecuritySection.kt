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

private const val LABEL_REQUIRE_UNLOCK = "Unlock to open Keyholm"
private const val LABEL_REQUIRE_UNLOCK_SEE = "Unlock to see passkeys"
private const val DESCRIPTION_REQUIRE_UNLOCK = "Protects settings and the list of accounts."
private const val DESCRIPTION_REQUIRE_UNLOCK_SEE = "Protects the list of accounts when authenticating."

@Composable
private fun LockRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(label)
    }
}

@Composable
internal fun SecuritySection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
) {
    Section(title = SECTION_TITLE_SECURITY) {
        item { shape ->
            LockRow(
                LABEL_REQUIRE_UNLOCK,
                DESCRIPTION_REQUIRE_UNLOCK,
                uiState.settings.appLock,
                viewModel.settings::setAppLock,
                shape,
            )
        }
        item { shape ->
            LockRow(
                LABEL_REQUIRE_UNLOCK_SEE,
                DESCRIPTION_REQUIRE_UNLOCK_SEE,
                uiState.settings.requireUnlock,
                viewModel.settings::setRequireUnlock,
                shape,
            )
        }
    }
}
