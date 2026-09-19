package app.keyholm.ui.createpasskey

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keyholm.ui.common.Section
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.WebAuthnAlgorithm

private val HeadlineTrim =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.LastLineBottom,
    )
private val SupportingTrim =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.FirstLineTop,
    )

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
)

internal data class CreationChoice(
    val algorithm: WebAuthnAlgorithm,
    val includeAttestation: Boolean,
    val identifyAsKeyholm: Boolean,
)

private fun creationOptionsExplanation(
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
        if (configured.isNotEmpty()) {
            add("You've configured Keyholm to ask you " + configured.joinToString(" and ") + ".")
        }
        if (showAttestation) add("Keyholm always asks whether to include attestation.")
    }.joinToString(" ")
}

@Composable
private fun CreationOptionsHeader(
    icon: ImageBitmap,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
        Text(
            "Keyholm",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = { onCheckedChange(!checked) },
        supportingContent =
            subtitle?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = SupportingTrim),
                    )
                }
            },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = HeadlineTrim),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AlgorithmRow(
    algorithm: WebAuthnAlgorithm,
    icon: ImageBitmap,
    shape: Shape,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 60.dp),
        supportingContent =
            if (algorithm == WebAuthnAlgorithm.ES256) {
                {
                    Text(
                        "Uses dedicated hardware",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = SupportingTrim),
                    )
                }
            } else {
                null
            },
        leadingContent = {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(CircleShape),
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(
            algorithm.displayName,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = HeadlineTrim),
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreationOptionsSheet(
    prompt: CreationOptionsPrompt,
    onChoice: (CreationChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val explanation =
        creationOptionsExplanation(
            prompt.algorithms.size > 1,
            prompt.attestation is OptionChoice.Ask,
            prompt.identity is OptionChoice.Ask,
        )
    var includeAttestation by remember { mutableStateOf(prompt.attestation.initial) }
    var identifyAsKeyholm by remember { mutableStateOf(prompt.identity.initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceBright,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        CreationOptionsSheetContent(
            prompt = prompt,
            explanation = explanation,
            includeAttestation = includeAttestation,
            onIncludeAttestationChange = { includeAttestation = it },
            identifyAsKeyholm = identifyAsKeyholm,
            onIdentifyChange = { identifyAsKeyholm = it },
            onChoice = onChoice,
        )
    }
}

@Composable
private fun CreationOptionsSheetContent(
    prompt: CreationOptionsPrompt,
    explanation: String,
    includeAttestation: Boolean,
    onIncludeAttestationChange: (Boolean) -> Unit,
    identifyAsKeyholm: Boolean,
    onIdentifyChange: (Boolean) -> Unit,
    onChoice: (CreationChoice) -> Unit,
) {
    val icon = rememberAppIcon(badged = true)
    CreationOptionsHeader(
        icon,
        "Create passkey to sign in as ${prompt.userName} to ${prompt.rpId.value}?",
    )
    if (prompt.attestation is OptionChoice.Ask || prompt.identity is OptionChoice.Ask) {
        CreationTogglesSection(
            askAttestation = prompt.attestation is OptionChoice.Ask,
            includeAttestation = includeAttestation,
            onIncludeAttestationChange = onIncludeAttestationChange,
            askIdentity = prompt.identity is OptionChoice.Ask,
            identifyAsKeyholm = identifyAsKeyholm,
            onIdentifyChange = onIdentifyChange,
        )
    }
    AlgorithmSection(
        algorithms = prompt.algorithms,
        icon = icon,
        onSelect = { onChoice(CreationChoice(it, includeAttestation, identifyAsKeyholm)) },
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
private fun CreationTogglesSection(
    askAttestation: Boolean,
    includeAttestation: Boolean,
    onIncludeAttestationChange: (Boolean) -> Unit,
    askIdentity: Boolean,
    identifyAsKeyholm: Boolean,
    onIdentifyChange: (Boolean) -> Unit,
) {
    Section(
        title = null,
        outerRadius = 28.dp,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        if (askIdentity) {
            item { rowShape ->
                ToggleRow(
                    title = "Identify as Keyholm",
                    subtitle = "Include Keyholm's identifier (AAGUID).",
                    checked = identifyAsKeyholm,
                    onCheckedChange = onIdentifyChange,
                    shape = rowShape,
                )
            }
        }
        if (askAttestation) {
            item { rowShape ->
                ToggleRow(
                    title = "Include attestation",
                    subtitle = "Attestation may reveal identifying information.",
                    checked = includeAttestation,
                    onCheckedChange = onIncludeAttestationChange,
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
    Section(
        title = null,
        outerRadius = 28.dp,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        algorithms.forEach { algorithm ->
            item { rowShape ->
                AlgorithmRow(
                    algorithm = algorithm,
                    icon = icon,
                    shape = rowShape,
                    onClick = { onSelect(algorithm) },
                )
            }
        }
    }
}
