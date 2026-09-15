package app.keyholm.ui.main

import android.content.Context
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.theme.titleColor
import app.keyholm.webauthn.CredentialId
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

private val log = Logger.withTag("app.keyholm.ui.main.MainScreen")

private sealed interface DeletePlan {
    data class Confirm(
        val cryptoObject: BiometricPrompt.CryptoObject?,
        val allowedAuthenticators: AuthenticatorPolicy,
    ) : DeletePlan

    data object Orphaned : DeletePlan

    data class Failed(
        val message: String,
    ) : DeletePlan
}

private fun buildDeletePlan(record: PasskeyRecord): DeletePlan {
    val algorithm = record.keystore.coseAlgorithm
    val keyManager = SecureKeyManager()
    val allowedAuthenticators =
        try {
            keyManager.allowedAuthenticatorsFor(record.keyAlias, algorithm)
        } catch (e: UnrecoverableKeyException) {
            log.e(e) { "no key material for ${record.keyAlias.value}, treating as orphaned" }
            return DeletePlan.Orphaned
        } catch (e: GeneralSecurityException) {
            log.e(e) { "couldn't read authenticators for ${record.keyAlias.value}" }
            return DeletePlan.Failed(ErrorMessages.DELETE_KEY_UNAVAILABLE)
        } catch (e: ProviderException) {
            log.e(e) { "couldn't read authenticators for ${record.keyAlias.value}" }
            return DeletePlan.Failed(ErrorMessages.DELETE_KEY_UNAVAILABLE)
        }
    val cryptoObject =
        try {
            BiometricPrompt.CryptoObject(keyManager.signatureFor(record.keyAlias, algorithm))
        } catch (e: GeneralSecurityException) {
            log.e(e) { "couldn't create a signature for ${record.keyAlias.value}" }
            null
        }
    return DeletePlan.Confirm(cryptoObject, allowedAuthenticators)
}

@Composable
private fun HandleDeleteUndo(
    pendingDeleteBatch: List<PasskeyRecord>,
    snackbarHostState: SnackbarHostState,
    rowGeneration: MutableMap<CredentialId, Int>,
    onUndo: () -> Unit,
) {
    LaunchedEffect(pendingDeleteBatch) {
        if (pendingDeleteBatch.isEmpty()) return@LaunchedEffect
        val mostRecent = pendingDeleteBatch.last()
        val othersCount = pendingDeleteBatch.size - 1
        val message =
            "Deleted ${mostRecent.user.name}" +
                if (othersCount > 0) " and $othersCount other passkey${if (othersCount == 1) "" else "s"}" else ""
        val result =
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            // Force a fresh SwipeToDismissBoxState for every row in the batch: without this,
            // LazyColumn restores each row's old (fully-swiped) saved state under the same key
            // and immediately re-fires onDismiss, looping the delete right back on.
            pendingDeleteBatch.forEach { rowGeneration[it.credentialId] = (rowGeneration[it.credentialId] ?: 0) + 1 }
            onUndo()
        }
    }
}

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

internal class DeleteConfirmationRequest(
    val record: PasskeyRecord,
    val onConfirmed: () -> Unit,
    val onDenied: () -> Unit,
)

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
    val onDismissPlaceholder: (MigrationPlaceholder) -> Unit,
    val showMessage: (String) -> Unit,
)

private fun LazyListScope.migrationPlaceholderItems(
    placeholders: List<MigrationPlaceholder>,
    display: PlaceholderRowDisplay,
    actions: PasskeyListActions,
) {
    if (!placeholders.isEmpty()) {
        item {
            Text(
                QUEUED_FOR_RECREATION_LABEL,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    items(
        placeholders,
        key = { "${it.rp.id.value}:${it.userName}" },
    ) { placeholder ->
        MigrationPlaceholderItem(
            placeholder = placeholder,
            display = display,
            onClick = { actions.showMessage("Visit ${placeholder.rp.id.value} to recreate this passkey!") },
            onDismiss = {
                actions.onDismissPlaceholder(placeholder)
                actions.showMessage("Removed placeholder ${placeholder.userName}")
            },
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun PasskeyListContent(
    uiState: MainUiState.Ready,
    iconPack: IconPack?,
    innerPadding: PaddingValues,
    rowGeneration: MutableMap<CredentialId, Int>,
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
                    isAvailable = uiState.device.secureElement,
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
        passkeyItems(
            passkeys.value,
            PasskeyRowDisplay(uiState.settings.compactView, uiState.settings.preferRpName, iconPack),
            rowGeneration,
            actions.rows,
        )
        migrationPlaceholderItems(placeholders.value, PlaceholderRowDisplay(uiState.settings.preferRpName, iconPack), actions)
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
    val rowGeneration = remember { mutableStateMapOf<CredentialId, Int>() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    HandleDeleteUndo(uiState.pendingDeleteBatch, snackbarHostState, rowGeneration, viewModel.pendingDeletes::undoAll)

    // This is for when multiple rows are swiped before the prompt shows up
    val confirmationQueue = remember { Channel<DeleteConfirmationRequest>(Channel.UNLIMITED) }
    val requestDeleteConfirmation: DeleteConfirmationRequester = { confirmationQueue.trySend(it) }
    val scope = rememberCoroutineScope()
    val listActions =
        PasskeyListActions(
            rows =
                PasskeyRowActions(
                    onDelete = viewModel.pendingDeletes::start,
                    onOpenDetails = onOpenDetails,
                    onCancelDelete = viewModel.pendingDeletes::cancel,
                    requestDeleteConfirmation = requestDeleteConfirmation,
                ),
            onDismissWarning = viewModel.settings::dismissDeviceBoundWarning,
            onOpenSettings = { openCredentialSettings(context) },
            onDismissPlaceholder = viewModel::dismissMigrationPlaceholder,
            showMessage = { scope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) } },
        )

    LaunchedEffect(cryptoPrompt) {
        for (request in confirmationQueue) {
            val confirmed =
                when (val plan = buildDeletePlan(request.record)) {
                    DeletePlan.Orphaned -> {
                        true
                    }

                    is DeletePlan.Failed -> {
                        viewModel.reportError(plan.message)
                        false
                    }

                    is DeletePlan.Confirm -> {
                        cryptoPrompt.confirm(
                            title = "Delete passkey",
                            cryptoObject = plan.cryptoObject,
                            allowedAuthenticators = plan.allowedAuthenticators,
                            content =
                                promptContent(
                                    description = "Confirm your identity to delete this passkey:",
                                    record = request.record,
                                    preferRpName = uiState.settings.preferRpName,
                                ),
                        )
                    }
                }
            if (confirmed) request.onConfirmed() else request.onDenied()
        }
    }

    Scaffold(topBar = { MainTopBar(onOpenSettings) }) { innerPadding ->
        PasskeyListContent(uiState, iconPack, innerPadding, rowGeneration, listActions)
    }
}

private fun openCredentialSettings(context: Context) {
    CredentialManager.create(context).createSettingsPendingIntent().send()
}
