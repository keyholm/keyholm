package app.keyholm.ui.main.settings

import android.widget.Toast
import androidx.biometric.AuthenticationRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.Section
import app.keyholm.ui.common.resolveCreateAuthenticators
import app.keyholm.ui.main.LOAD_ERROR
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.ui.main.StatusRow
import app.keyholm.ui.main.Stored
import app.keyholm.webauthn.CommunityAssetLinks
import app.keyholm.webauthn.NativeAppTrust
import kotlinx.coroutines.launch

private const val TITLE_SETTINGS = "Settings"
private const val CONTENT_DESCRIPTION_BACK = "Back"
private const val CONTENT_DESCRIPTION_HELP = "Help"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    TopAppBar(
        title = { Text(TITLE_SETTINGS) },
        navigationIcon = {
            FilledIconButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = CONTENT_DESCRIPTION_BACK)
            }
        },
        actions = {
            IconButton(onClick = onOpenHelp) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = CONTENT_DESCRIPTION_HELP)
            }
        },
    )
}

private const val STATUS_TEE_OK = "Hardware-backed (TEE) keystore found."
private const val STATUS_TEE_NOT_OK = "No hardware-backed (TEE) keystore found on this device."
internal const val STATUS_SECURE_ELEMENT_OK = "Dedicated secure element (StrongBox) found."
internal const val STATUS_SECURE_ELEMENT_NOT_OK =
    "No dedicated secure element (StrongBox) found. This app is not supported."
private const val STATUS_PROVIDER_OK = "Keyholm is enabled as a passkey service."
private const val STATUS_PROVIDER_NOT_OK = "Keyholm is not enabled as a passkey service."

@Composable
private fun StatusSection(uiState: MainUiState.Ready) {
    Section(title = SECTION_TITLE_STATUS) {
        item { shape ->
            Column(
                Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
            ) {
                StatusRow(
                    isOk = uiState.device.tee,
                    okText = STATUS_TEE_OK,
                    notOkText = STATUS_TEE_NOT_OK,
                )
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    isOk = uiState.device.secureElement,
                    okText = STATUS_SECURE_ELEMENT_OK,
                    notOkText = STATUS_SECURE_ELEMENT_NOT_OK,
                )
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    isOk = uiState.device.providerEnabled,
                    okText = STATUS_PROVIDER_OK,
                    notOkText = STATUS_PROVIDER_NOT_OK,
                )
            }
        }
    }
}

private const val LABEL_EXCEPTIONS = "Exceptions"

@Composable
private fun NativeAppTrustSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    onOpenExceptions: () -> Unit,
) {
    Section(title = SECTION_TITLE_TRUST_BEHAVIOR) {
        item { shape -> NativeAppTrustRow(uiState, viewModel, shape) }
        if (uiState.settings.nativeAppTrust != NativeAppTrust.AllowAll) {
            attachedItem { shape ->
                val nativeApps = uiState.nativeApps
                val undecided = if (nativeApps is Stored.Available) nativeApps.value.denied.size else 0
                ListItem(
                    selected = false,
                    onClick = onOpenExceptions,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (undecided > 0) {
                                Badge { Text(undecided.toString()) }
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.shapes(shape = shape),
                ) {
                    Text(LABEL_EXCEPTIONS, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private const val LABEL_DENY_ALL = "Deny all"
private const val LABEL_ALLOW_ALL = "Allow all"
private const val LABEL_COMMUNITY = "Community"
private const val LABEL_NATIVE_APPS = "Native apps"
private const val DESCRIPTION_DENY_ALL = "No native apps can use passkeys."
private const val DESCRIPTION_ALLOW_ALL = "Any native app can use passkeys for any RP."
private const val DESCRIPTION_COMMUNITY = "Use Digital Asset Links with a community-maintained list of RPs."

@Composable
private fun NativeAppTrustRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentLabel =
        when (uiState.settings.nativeAppTrust) {
            NativeAppTrust.DenyAll -> LABEL_DENY_ALL
            NativeAppTrust.AllowAll -> LABEL_ALLOW_ALL
            is NativeAppTrust.Community -> LABEL_COMMUNITY
        }
    val description =
        when (uiState.settings.nativeAppTrust) {
            NativeAppTrust.DenyAll -> DESCRIPTION_DENY_ALL
            NativeAppTrust.AllowAll -> DESCRIPTION_ALLOW_ALL
            is NativeAppTrust.Community -> DESCRIPTION_COMMUNITY
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
                        text = { Text(LABEL_DENY_ALL) },
                        onClick = {
                            viewModel.settings.setNativeAppTrust(NativeAppTrust.DenyAll)
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LABEL_ALLOW_ALL) },
                        onClick = {
                            viewModel.settings.setNativeAppTrust(NativeAppTrust.AllowAll)
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LABEL_COMMUNITY) },
                        onClick = {
                            val trust = NativeAppTrust.Community(CommunityAssetLinks.load(context))
                            viewModel.settings.setNativeAppTrust(trust)
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
        Text(LABEL_NATIVE_APPS)
    }
}

private const val LABEL_RESET_SETTINGS = "Settings"
private const val LABEL_ALL_DATA = "All data"
private const val DESCRIPTION_ALL_DATA = "Deletes every passkey and its hardware key. This cannot be undone."

@Composable
private fun ResetSection(
    viewModel: MainViewModel,
    onRequestResetConfirm: () -> Unit,
) {
    Section(title = SECTION_TITLE_RESET) {
        item { shape ->
            ListItem(
                selected = false,
                onClick = { viewModel.settings.resetSettings() },
                leadingContent = { Icon(Icons.Default.Replay, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
                shapes = ListItemDefaults.shapes(shape = shape),
            ) {
                Text(LABEL_RESET_SETTINGS)
            }
        }
        item { shape ->
            ListItem(
                selected = false,
                onClick = onRequestResetConfirm,
                supportingContent = {
                    Text(DESCRIPTION_ALL_DATA, style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
                shapes = ListItemDefaults.shapes(shape = shape),
            ) {
                Text(LABEL_ALL_DATA, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private const val DIALOG_TITLE_RESET = "Reset Keyholm?"
private const val DIALOG_BODY_RESET_TEMPLATE =
    "This permanently deletes all %d stored passkeys and their " +
        "keys in the secure element. Every site you've registered with will need a new " +
        "passkey. Consider exporting the account list above. This cannot be undone."
private const val BUTTON_RESET = "Reset"
private const val BUTTON_CANCEL = "Cancel"

@Composable
private fun ResetConfirmDialog(
    passkeys: Stored<List<PasskeyRecord>>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DIALOG_TITLE_RESET) },
        text = {
            if (passkeys is Stored.Available) {
                Text(DIALOG_BODY_RESET_TEMPLATE.format(passkeys.value.size))
            } else {
                Text(LOAD_ERROR)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(BUTTON_RESET, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(BUTTON_CANCEL) }
        },
    )
}

private const val CONFIRM_TITLE_RESET_ALL = "Reset all data"
private const val CONFIRM_DESCRIPTION_RESET_ALL = "Confirm your identity to delete all Keyholm data."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenExceptions: () -> Unit,
    viewModel: MainViewModel = viewModel(),
    cryptoPrompt: CryptoPrompt,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state as? MainUiState.Ready ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { SettingsTopBar(onBack, onOpenHelp) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
        ) {
            StatusSection(uiState)
            SecuritySection(uiState, viewModel)
            InterfaceSection(uiState, viewModel)
            NativeAppTrustSection(uiState, viewModel, onOpenExceptions)
            KeyCreationSection(uiState, viewModel)
            AlgorithmSection(uiState, viewModel)
            AccountMigrationSection(uiState, viewModel, context)
            ResetSection(viewModel, onRequestResetConfirm = { showResetConfirm = true })
        }
    }

    if (showResetConfirm) {
        ResetConfirmDialog(
            passkeys = uiState.passkeys,
            onConfirm = {
                showResetConfirm = false
                scope.launch {
                    val policy = uiState.settings.createAuthenticators
                    when (val authenticators = resolveCreateAuthenticators(context, policy)) {
                        is AuthenticatorsResolution.Unavailable -> {
                            Toast.makeText(context, authenticators.message, Toast.LENGTH_LONG).show()
                        }

                        is AuthenticatorsResolution.Ready -> {
                            val confirmed =
                                cryptoPrompt.confirm(
                                    CONFIRM_TITLE_RESET_ALL,
                                    allowedAuthenticators = authenticators.value,
                                    content = AuthenticationRequest.BodyContent.PlainText(CONFIRM_DESCRIPTION_RESET_ALL),
                                )
                            if (confirmed) viewModel.resetEverything()
                        }
                    }
                }
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}
