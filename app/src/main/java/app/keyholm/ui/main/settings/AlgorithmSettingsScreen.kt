package app.keyholm.ui.main.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmNegotiation
import app.keyholm.webauthn.AlgorithmPreference

private const val DESCRIPTION_SIGNATURE_ALGORITHMS =
    "Algorithm selection during creation. At least one must be enabled."
private const val DESCRIPTION_ES256 = "Supported by StrongBox."
private const val DESCRIPTION_ED25519 = "Falls back to the TEE."

@Composable
internal fun AlgorithmSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
) {
    Section(
        title = SECTION_TITLE_SIGNATURE_ALGORITHMS,
        description = DESCRIPTION_SIGNATURE_ALGORITHMS,
    ) {
        AlgorithmFamily.entries.forEach { family ->
            item { shape ->
                when (family) {
                    AlgorithmFamily.ES256 -> {
                        AlgorithmSwitchRow(family, DESCRIPTION_ES256, uiState, viewModel, shape)
                    }

                    AlgorithmFamily.ED25519 -> {
                        AlgorithmSwitchRow(family, DESCRIPTION_ED25519, uiState, viewModel, shape)
                    }

                    AlgorithmFamily.ML_DSA -> {
                        MlDsaRow(uiState, viewModel, shape)
                    }
                }
            }
        }
        item { shape -> PreferredAlgorithmRow(uiState, viewModel, shape) }
        val fallbackOptions = fallbackOptions(uiState)
        if (fallbackOptions.size > 1) {
            attachedItem { shape -> FallbackAlgorithmRow(uiState, viewModel, fallbackOptions, shape) }
        }
    }
}

@Composable
private fun AlgorithmSwitchRow(
    family: AlgorithmFamily,
    description: String,
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(
                checked = family in uiState.settings.enabledFamilies,
                onCheckedChange = { viewModel.settings.algorithms.setEnabled(family, it) },
                enabled = offersSomethingWithout(uiState, family),
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(family.displayName)
    }
}

// Creation needs an algorithm left over, so the last family standing can't be switched off.
internal fun offersSomethingWithout(
    uiState: MainUiState.Ready,
    family: AlgorithmFamily,
): Boolean =
    allowedFamilies(uiState).any {
        it !=
            family
    }

private const val LABEL_FIRST_OFFERED = "First offered"
private const val LABEL_ALWAYS_ASK = "Always ask"
private const val LABEL_PREFERRED_ALGORITHM = "Preferred algorithm"
private const val LABEL_FALLBACK_ALGORITHM = "Fallback algorithm preference"
private const val DESCRIPTION_FALLBACK_ALGORITHM =
    "Applies if the preferred algorithm isn't available."
private const val DESCRIPTION_FIRST_OFFERED = "Use first enabled algorithm offered by the relying party."
private const val DESCRIPTION_ALWAYS_ASK = "Choose from offered, enabled algorithms at creation."
private const val DESCRIPTION_PREFER_STRONGBOX = "Prefer StrongBox."

@Composable
private fun AlgorithmPreferenceDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canChoose: Boolean,
    currentLabel: String,
    options: List<AlgorithmFamily>,
    onSelect: (AlgorithmPreference) -> Unit,
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }, enabled = canChoose) {
            Text(currentLabel)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(LABEL_FIRST_OFFERED) },
                onClick = {
                    onSelect(AlgorithmPreference.FirstOffered)
                    onExpandedChange(false)
                },
            )
            options.forEach { family ->
                DropdownMenuItem(
                    text = { Text(family.displayName) },
                    onClick = {
                        onSelect(AlgorithmPreference.Prefer(family))
                        onExpandedChange(false)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(LABEL_ALWAYS_ASK) },
                onClick = {
                    onSelect(AlgorithmPreference.AlwaysAsk)
                    onExpandedChange(false)
                },
            )
        }
    }
}

@Composable
private fun PreferredAlgorithmRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    val preference = uiState.settings.preferredAlgorithm
    val description =
        when (preference) {
            AlgorithmPreference.FirstOffered -> {
                DESCRIPTION_FIRST_OFFERED
            }

            AlgorithmPreference.AlwaysAsk -> {
                DESCRIPTION_ALWAYS_ASK
            }

            is AlgorithmPreference.Prefer -> {
                DESCRIPTION_PREFER_STRONGBOX.takeIf { preference.family == AlgorithmFamily.ES256 }
            }
        }
    AlgorithmPreferenceRow(
        label = LABEL_PREFERRED_ALGORITHM,
        description = description,
        preference = preference,
        options = allowedFamilies(uiState),
        onSelect = viewModel.settings.algorithms::setPreferred,
        shape = shape,
    )
}

private fun allowedFamilies(uiState: MainUiState.Ready): List<AlgorithmFamily> =
    AlgorithmNegotiation
        .allowedAlgorithms(uiState.settings.enabledFamilies, uiState.settings.mlDsaSupport)
        .map { it.family }
        .distinct()

// The fallback only decides between the families left once the preferred one is out, so it needs two of them.
private fun fallbackOptions(uiState: MainUiState.Ready): List<AlgorithmFamily> {
    val preferred =
        (uiState.settings.preferredAlgorithm as? AlgorithmPreference.Prefer)?.family
            ?: return emptyList()
    return allowedFamilies(uiState).filter { it != preferred }
}

@Composable
private fun FallbackAlgorithmRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    options: List<AlgorithmFamily>,
    shape: Shape,
) {
    AlgorithmPreferenceRow(
        label = LABEL_FALLBACK_ALGORITHM,
        description = DESCRIPTION_FALLBACK_ALGORITHM,
        preference = uiState.settings.fallbackAlgorithm,
        options = options,
        onSelect = viewModel.settings.algorithms::setFallback,
        shape = shape,
        attached = true,
    )
}

@Composable
private fun AlgorithmPreferenceRow(
    label: String,
    description: String?,
    preference: AlgorithmPreference,
    options: List<AlgorithmFamily>,
    onSelect: (AlgorithmPreference) -> Unit,
    shape: Shape,
    attached: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val canChoose = options.size > 1
    val currentLabel =
        when (preference) {
            AlgorithmPreference.FirstOffered -> LABEL_FIRST_OFFERED
            AlgorithmPreference.AlwaysAsk -> LABEL_ALWAYS_ASK
            is AlgorithmPreference.Prefer -> preference.family.displayName
        }

    ListItem(
        selected = false,
        onClick = {},
        supportingContent =
            description?.let { text ->
                { Text(text, style = MaterialTheme.typography.bodySmall) }
            },
        trailingContent = {
            AlgorithmPreferenceDropdown(expanded, { expanded = it }, canChoose, currentLabel, options, onSelect)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        val alpha = if (canChoose) 1f else 0.38f
        Text(
            label,
            color = LocalContentColor.current.copy(alpha = alpha),
            style = if (attached) MaterialTheme.typography.bodyMedium else LocalTextStyle.current,
        )
    }
}
