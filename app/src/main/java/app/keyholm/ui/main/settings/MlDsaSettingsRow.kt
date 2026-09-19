package app.keyholm.ui.main.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.MlDsaSupport

private const val LABEL_ML_DSA_OFF = "Off"
private const val LABEL_ML_DSA_STRONGEST_ONLY = "ML-DSA-87 only"
private const val LABEL_ML_DSA_PREFER_STRONGEST = "Prefer strongest"
private const val LABEL_ML_DSA_FIRST_OFFERED = "First offered"
private const val DESCRIPTION_ML_DSA_TEE = "Falls back to the TEE."

private fun mlDsaLabel(support: MlDsaSupport): String =
    when (support) {
        MlDsaSupport.OFF -> LABEL_ML_DSA_OFF
        MlDsaSupport.STRONGEST_ONLY -> LABEL_ML_DSA_STRONGEST_ONLY
        MlDsaSupport.PREFER_STRONGEST -> LABEL_ML_DSA_PREFER_STRONGEST
        MlDsaSupport.FIRST_OFFERED -> LABEL_ML_DSA_FIRST_OFFERED
    }

@Composable
internal fun MlDsaRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            Text(DESCRIPTION_ML_DSA_TEE, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(mlDsaLabel(uiState.settings.mlDsaSupport))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MlDsaSupport.entries.forEach { support ->
                        DropdownMenuItem(
                            text = { Text(mlDsaLabel(support)) },
                            enabled =
                                support != MlDsaSupport.OFF ||
                                    offersSomethingWithout(uiState, AlgorithmFamily.ML_DSA),
                            onClick = {
                                viewModel.settings.algorithms.setMlDsaSupport(support)
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(AlgorithmFamily.ML_DSA.displayName)
    }
}
