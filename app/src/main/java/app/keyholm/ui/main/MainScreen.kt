package app.keyholm.ui.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.keyholm.iconpack.IconPack
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.theme.titleColor
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                "Keyholm",
                color = titleColor,
            )
        },
        navigationIcon = {
            Image(
                bitmap = rememberAppIcon(),
                contentDescription = null,
                modifier = Modifier.padding(start = 20.dp, end = 12.dp).size(40.dp).clip(CircleShape),
            )
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    )
}

private fun LazyListScope.passkeyItems(
    passkeys: List<PasskeyRecord>,
    display: PasskeyRowDisplay,
    rowGeneration: Map<CredentialId, Int>,
    actions: PasskeyRowActions,
) {
    items(
        passkeys,
        key = { "${it.credentialId.b64}:${rowGeneration[it.credentialId] ?: 0}" },
    ) { record ->
        PasskeyItem(
            record = record,
            display = display,
            actions = actions,
            modifier = Modifier.animateItem(),
        )
    }
}

private data class PasskeyListActions(
    val rows: PasskeyRowActions,
    val onDismissWarning: () -> Unit,
    val onOpenSettings: () -> Unit,
    val placeholderRows: PlaceholderRowActions,
)

private fun LazyListScope.migrationPlaceholderItems(
    placeholders: List<MigrationPlaceholder>,
    display: PlaceholderRowDisplay,
    rowGeneration: Map<Pair<RpId, String>, Int>,
    actions: PlaceholderRowActions,
) {
    items(
        placeholders,
        key = { "${it.rp.id.value}:${it.userName}:${rowGeneration[it.rp.id to it.userName] ?: 0}" },
    ) { placeholder ->
        MigrationPlaceholderItem(
            placeholder = placeholder,
            display = display,
            actions = actions,
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun PasskeyListContent(
    uiState: MainUiState.Ready,
    iconPack: IconPack?,
    innerPadding: PaddingValues,
    rowGenerations: RowGenerations,
    actions: PasskeyListActions,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!uiState.settings.deviceBoundWarningDismissed) {
            item {
                WarningCard(
                    message = "Keyholm passkeys are strictly device-bound. They:",
                    bullets =
                        listOf(
                            "cannot be exported or synced",
                            "become unusable if using the device credential and you disable the secure lock screen",
                            "become unusable if biometrics-only and you change biometrics enrollment, by default",
                            "are irrevocably deleted on uninstall",
                        ),
                    onDismiss = actions.onDismissWarning,
                )
            }
        }
        if (!uiState.device.providerEnabled) {
            item {
                StatusCard(
                    device = uiState.device,
                    onOpenSettings = actions.onOpenSettings,
                )
            }
        }
        val passkeys = uiState.passkeys
        val placeholders = uiState.migrationPlaceholders
        if (passkeys !is Stored.Available || placeholders !is Stored.Available) {
            item { LoadErrorCard(modifier = Modifier.fillParentMaxSize()) }
            return@LazyColumn
        }
        if (passkeys.value.isEmpty() && placeholders.value.isEmpty()) {
            item { EmptyPasskeysMessage(modifier = Modifier.fillParentMaxSize()) }
        }
        migrationPlaceholderItems(
            placeholders.value,
            PlaceholderRowDisplay(uiState.settings.preferRpName, iconPack),
            rowGenerations.placeholders,
            actions.placeholderRows,
        )
        passkeyItems(
            passkeys.value,
            PasskeyRowDisplay(uiState.settings.compactView, uiState.settings.preferRpName, iconPack),
            rowGenerations.passkeys,
            actions.rows,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    cryptoPrompt: CryptoPrompt,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenDetails: (PasskeyRecord) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state as? MainUiState.Ready ?: return
    val iconPack by viewModel.iconPacks.pack.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rowGenerations = remember { RowGenerations(mutableStateMapOf(), mutableStateMapOf()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    PendingDeleteUndo(uiState.pendingDeleteBatches, snackbarHostState, rowGenerations, uiState.settings.preferRpName) {
        viewModel.pendingDeletes.undoAll()
        viewModel.placeholderDeletes.undoAll()
    }

    // This is for when multiple rows are swiped before the prompt shows up
    val confirmationQueue = remember { Channel<DeleteConfirmationRequest>(Channel.UNLIMITED) }
    val requestPasskeyDeleteConfirmation =
        passkeyDeleteRequester(confirmationQueue, uiState.settings.preferRpName, viewModel.pendingDeletes::planFor)
    val scope = rememberCoroutineScope()
    val listActions =
        PasskeyListActions(
            rows =
                PasskeyRowActions(
                    onDelete = viewModel.pendingDeletes::start,
                    onOpenDetails = onOpenDetails,
                    onCancelDelete = viewModel.pendingDeletes::cancel,
                    requestDeleteConfirmation = requestPasskeyDeleteConfirmation,
                ),
            onDismissWarning = viewModel.settings::dismissDeviceBoundWarning,
            onOpenSettings = { openCredentialSettings(context) },
            placeholderRows =
                PlaceholderRowActions(
                    onClick = {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Visit ${it.rp.id.value} to recreate this passkey!",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    onDelete = viewModel.placeholderDeletes::start,
                    onCancelDelete = viewModel.placeholderDeletes::cancel,
                ),
        )

    HandleDeleteConfirmations(
        confirmationQueue = confirmationQueue,
        cryptoPrompt = cryptoPrompt,
        reportError = viewModel.reportError,
    )

    Scaffold(topBar = { MainTopBar(onOpenSettings) }) { innerPadding ->
        PasskeyListContent(uiState, iconPack, innerPadding, rowGenerations, listActions)
    }
}

private fun openCredentialSettings(context: Context) {
    CredentialManager.create(context).createSettingsPendingIntent().send()
}
