package app.keyholm.ui.createpasskey

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyholm.ui.common.Sheet
import app.keyholm.ui.common.SheetHeader
import app.keyholm.ui.common.SheetHeadlineTrim
import app.keyholm.ui.common.SheetRow
import app.keyholm.ui.common.SheetSection
import app.keyholm.ui.common.SheetSupportingTrim
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.WebAuthnAlgorithm

internal sealed interface OptionChoice {
    val initial: Boolean

    data class Fixed(
        val value: Boolean,
    ) : OptionChoice {
        override val initial: Boolean get() = value
    }

    data class Ask(
        override val initial: Boolean,
    ) : OptionChoice
}

internal data class CreationOptionsPrompt(
    val rpId: RpId,
    val userName: String,
    val algorithms: List<WebAuthnAlgorithm>,
    val attestation: OptionChoice,
    val identity: OptionChoice,
    val devicePropertiesAvailable: Boolean,
)

private data class CreationToggle(
    val checked: Boolean,
    val enabled: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

private data class CreationToggles(
    val attestation: CreationToggle?,
    val deviceProperties: CreationToggle?,
    val identity: CreationToggle?,
)

internal data class CreationChoice(
    val algorithm: WebAuthnAlgorithm,
    val includeAttestation: Boolean,
    val includeDeviceProperties: Boolean,
    val identifyAsKeyholm: Boolean,
)

private fun creationOptionsExplanation(
    rpId: RpId,
    algorithmAsked: Boolean,
    showAttestation: Boolean,
    showIdentity: Boolean,
): String {
    val configured =
        buildList {
            if (algorithmAsked) add("which algorithm to use")
            if (showIdentity) add("whether to identify as Keyholm")
        }
    return buildList {
        if (showAttestation) add("Attestation was requested by ${rpId.value} and Keyholm always asks whether to include it.")
        if (configured.isNotEmpty()) {
            add("You've configured Keyholm to ask you " + configured.joinToString(" and ") + ".")
        }
    }.joinToString(" ")
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    toggle: CreationToggle,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = { toggle.onCheckedChange(!toggle.checked) },
        enabled = toggle.enabled,
        supportingContent =
            subtitle?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = SheetSupportingTrim),
                    )
                }
            },
        trailingContent = { Switch(checked = toggle.checked, onCheckedChange = toggle.onCheckedChange, enabled = toggle.enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = SheetHeadlineTrim),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun CreationOptionsSheet(
    prompt: CreationOptionsPrompt,
    onChoice: (CreationChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val explanation =
        creationOptionsExplanation(
            prompt.rpId,
            prompt.algorithms.size > 1,
            prompt.attestation is OptionChoice.Ask,
            prompt.identity is OptionChoice.Ask,
        )
    var includeAttestation by remember { mutableStateOf(prompt.attestation.initial) }
    var includeDeviceProperties by remember { mutableStateOf(false) }
    var identifyAsKeyholm by remember { mutableStateOf(prompt.identity.initial) }
    val devicePropertiesEnabled = includeAttestation && prompt.devicePropertiesAvailable
    Sheet(onDismiss) {
        CreationOptionsSheetContent(
            prompt = prompt,
            explanation = explanation,
            toggles =
                CreationToggles(
                    attestation =
                        if (prompt.attestation is OptionChoice.Ask) {
                            CreationToggle(includeAttestation, enabled = true) { includeAttestation = it }
                        } else {
                            null
                        },
                    deviceProperties =
                        if (prompt.attestation is OptionChoice.Ask) {
                            CreationToggle(devicePropertiesEnabled && includeDeviceProperties, enabled = devicePropertiesEnabled) {
                                includeDeviceProperties = it
                            }
                        } else {
                            null
                        },
                    identity =
                        if (prompt.identity is OptionChoice.Ask) {
                            CreationToggle(identifyAsKeyholm, enabled = true) { identifyAsKeyholm = it }
                        } else {
                            null
                        },
                ),
            onSelectAlgorithm = {
                onChoice(CreationChoice(it, includeAttestation, devicePropertiesEnabled && includeDeviceProperties, identifyAsKeyholm))
            },
        )
    }
}

@Composable
private fun CreationOptionsSheetContent(
    prompt: CreationOptionsPrompt,
    explanation: String,
    toggles: CreationToggles,
    onSelectAlgorithm: (WebAuthnAlgorithm) -> Unit,
) {
    val icon = rememberAppIcon(badged = true)
    SheetHeader(
        icon,
        "Create passkey to sign in as ${prompt.userName} to ${prompt.rpId.value}?",
    )
    if (toggles.attestation != null || toggles.identity != null) {
        CreationTogglesSection(toggles)
    }
    AlgorithmSection(
        algorithms = prompt.algorithms,
        icon = icon,
        onSelect = onSelectAlgorithm,
    )
    HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
    Text(
        explanation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(56.dp))
}

@Composable
private fun CreationTogglesSection(toggles: CreationToggles) {
    SheetSection {
        if (toggles.identity != null) {
            item { rowShape ->
                ToggleRow(
                    title = "Identify as Keyholm",
                    subtitle = "Include Keyholm's identifier (AAGUID).",
                    toggle = toggles.identity,
                    shape = rowShape,
                )
            }
        }
        if (toggles.attestation != null) {
            item { rowShape ->
                ToggleRow(
                    title = "Include attestation",
                    subtitle = "Attestation may reveal identifying information.",
                    toggle = toggles.attestation,
                    shape = rowShape,
                )
            }
        }
        if (toggles.deviceProperties != null) {
            item { rowShape ->
                ToggleRow(
                    title = "Include device properties",
                    subtitle = "Includes the brand, manufacturer, model, device and product name.",
                    toggle = toggles.deviceProperties,
                    shape = rowShape,
                )
            }
        }
    }
}

@Composable
private fun AlgorithmSection(
    algorithms: List<WebAuthnAlgorithm>,
    icon: ImageBitmap,
    onSelect: (WebAuthnAlgorithm) -> Unit,
) {
    SheetSection {
        algorithms.forEach { algorithm ->
            item { rowShape ->
                SheetRow(
                    title = algorithm.displayName,
                    supporting = if (algorithm == WebAuthnAlgorithm.ES256) "Uses dedicated hardware" else null,
                    icon = icon,
                    shape = rowShape,
                    onClick = { onSelect(algorithm) },
                )
            }
        }
    }
}
