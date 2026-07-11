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
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.webauthn.IdentityPreference

private const val DESCRIPTION_KEY_SECTION = "Changes are only applied to new keys."
private const val LABEL_AUTHENTICATOR_IDENTITY = "Include authenticator identity"
private const val LABEL_IDENTITY_ALWAYS = "Always"
private const val LABEL_IDENTITY_NEVER = "Never"
private const val LABEL_IDENTITY_ALWAYS_ASK = "Always ask"
private const val DESCRIPTION_IDENTITY_ALWAYS =
    "Include Keyholm's identifier (AAGUID)."
private const val DESCRIPTION_IDENTITY_NEVER =
    "Include a blank identifier."
private const val DESCRIPTION_IDENTITY_ALWAYS_ASK = "Offer both options at creation time."
private const val LABEL_BIOMETRICS = "Biometrics"
private const val DESCRIPTION_BIOMETRICS =
    "Biometrics for authentication."
private const val LABEL_DEVICE_CREDENTIAL = "Device credential"
private const val DESCRIPTION_DEVICE_CREDENTIAL =
    "PIN/pattern/password for authentication."
private const val DESCRIPTION_DEVICE_CREDENTIAL_ENABLED =
    "$DESCRIPTION_DEVICE_CREDENTIAL\nKeys invalidated by disabling secure lock screen.\nKeys not invalidated by biometrics changes."
private const val LABEL_INVALIDATE_AFTER_ENROLLMENT_CHANGES = "Invalidate keys on enrollment changes"
private const val DESCRIPTION_INVALIDATE_AFTER_ENROLLMENT_CHANGES =
    "Keys invalidated by biometrics changes."

@Composable
private fun AuthenticatorIdentityRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel =
        when (uiState.settings.identityPreference) {
            IdentityPreference.ENABLED -> LABEL_IDENTITY_ALWAYS
            IdentityPreference.DISABLED -> LABEL_IDENTITY_NEVER
            IdentityPreference.ALWAYS_ASK -> LABEL_IDENTITY_ALWAYS_ASK
        }
    val description =
        when (uiState.settings.identityPreference) {
            IdentityPreference.ENABLED -> DESCRIPTION_IDENTITY_ALWAYS
            IdentityPreference.DISABLED -> DESCRIPTION_IDENTITY_NEVER
            IdentityPreference.ALWAYS_ASK -> DESCRIPTION_IDENTITY_ALWAYS_ASK
        }

    ListItem(
        selected = false,
        onClick = {},
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(currentLabel)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(LABEL_IDENTITY_ALWAYS) },
                        onClick = {
                            viewModel.settings.setIdentityPreference(IdentityPreference.ENABLED)
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LABEL_IDENTITY_NEVER) },
                        onClick = {
                            viewModel.settings.setIdentityPreference(IdentityPreference.DISABLED)
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LABEL_IDENTITY_ALWAYS_ASK) },
                        onClick = {
                            viewModel.settings.setIdentityPreference(IdentityPreference.ALWAYS_ASK)
                            expanded = false
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_AUTHENTICATOR_IDENTITY)
    }
}

@Composable
private fun BiometricsRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            Text(DESCRIPTION_BIOMETRICS, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = uiState.settings.createAuthenticators != AuthenticatorPolicy.DeviceCredential,
                onCheckedChange = { on ->
                    viewModel.settings.setCreateAuthenticators(
                        if (on) AuthenticatorPolicy.Either else AuthenticatorPolicy.DeviceCredential,
                    )
                },
                enabled = uiState.settings.createAuthenticators != AuthenticatorPolicy.Biometric,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_BIOMETRICS)
    }
}

@Composable
private fun DeviceCredentialRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            val description =
                if (uiState.settings.createAuthenticators == AuthenticatorPolicy.Biometric) {
                    DESCRIPTION_DEVICE_CREDENTIAL
                } else {
                    DESCRIPTION_DEVICE_CREDENTIAL_ENABLED
                }
            Text(description, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = uiState.settings.createAuthenticators != AuthenticatorPolicy.Biometric,
                onCheckedChange = { on ->
                    viewModel.settings.setCreateAuthenticators(
                        if (on) AuthenticatorPolicy.Either else AuthenticatorPolicy.Biometric,
                    )
                },
                enabled = uiState.settings.createAuthenticators != AuthenticatorPolicy.DeviceCredential,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_DEVICE_CREDENTIAL)
    }
}

@Composable
private fun InvalidateOnEnrollmentRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    ListItem(
        selected = false,
        onClick = {},
        supportingContent = {
            Text(DESCRIPTION_INVALIDATE_AFTER_ENROLLMENT_CHANGES, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Switch(
                checked = uiState.settings.invalidateOnBiometricEnrollment,
                onCheckedChange = viewModel.settings::setInvalidateOnBiometricEnrollment,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_INVALIDATE_AFTER_ENROLLMENT_CHANGES, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun KeyCreationSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
) {
    Section(title = SECTION_TITLE_KEY_CREATION, description = DESCRIPTION_KEY_SECTION) {
        item { shape -> AuthenticatorIdentityRow(uiState, viewModel, shape) }
        item { shape -> DeviceCredentialRow(uiState, viewModel, shape) }
        item { shape -> BiometricsRow(uiState, viewModel, shape) }
        if (uiState.settings.createAuthenticators == AuthenticatorPolicy.Biometric) {
            attachedItem { shape -> InvalidateOnEnrollmentRow(uiState, viewModel, shape) }
        }
    }
}
