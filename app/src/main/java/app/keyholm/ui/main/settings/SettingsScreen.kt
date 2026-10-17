package app.keyholm.ui.main.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.keyholm.ui.common.BackButton
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.DeviceStatusRows
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.ui.main.Stored
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.TrustMode

private const val TITLE_SETTINGS = "Settings"
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
            BackButton(onBack)
        },
        actions = {
            IconButton(onClick = onOpenHelp) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = CONTENT_DESCRIPTION_HELP)
            }
        },
    )
}

@Composable
private fun StatusSection(uiState: MainUiState.Ready) {
    Section(title = SECTION_TITLE_STATUS) {
        item { shape ->
            Column(
                Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            ) {
                DeviceStatusRows(uiState.device)
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
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
private fun NativeAppTrustMenu(
    currentLabel: String,
    onSelect: (TrustMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(currentLabel)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(LABEL_DENY_ALL) },
                onClick = {
                    onSelect(TrustMode.DenyAll)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(LABEL_ALLOW_ALL) },
                onClick = {
                    onSelect(TrustMode.AllowAll)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(LABEL_COMMUNITY) },
                onClick = {
                    onSelect(TrustMode.Community)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun NativeAppTrustRow(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    shape: Shape,
) {
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
        trailingContent = { NativeAppTrustMenu(currentLabel, viewModel.settings::setNativeAppTrust) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(LABEL_NATIVE_APPS)
    }
}

private const val LABEL_RESET_SETTINGS = "Settings"

@Composable
private fun ResetSection(viewModel: MainViewModel) {
    Section(title = SECTION_TITLE_RESET) {
        item { shape ->
            ListItem(
                selected = false,
                onClick = { viewModel.settings.resetSettings() },
                leadingContent = { Icon(Icons.Default.Replay, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                verticalAlignment = Alignment.CenterVertically,
                shapes = ListItemDefaults.shapes(shape = shape),
            ) {
                Text(LABEL_RESET_SETTINGS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenExceptions: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state as? MainUiState.Ready ?: return
    val context = LocalContext.current
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
            ResetSection(viewModel)
        }
    }
}
