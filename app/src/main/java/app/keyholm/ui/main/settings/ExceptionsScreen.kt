package app.keyholm.ui.main.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.keyholm.store.DeniedNativeAppInfo
import app.keyholm.store.DeniedNativeAppKey
import app.keyholm.store.exceptionKeys
import app.keyholm.store.toExceptionKeys
import app.keyholm.ui.common.BackButton
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.main.LoadErrorCard
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.ui.main.NativeAppExceptionAction
import app.keyholm.ui.main.Stored
import app.keyholm.ui.main.SwipeActionBackground
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.PackageName
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val SECTION_TITLE_DENIED = "Denied"
private const val SECTION_TITLE_EXCEPTIONS = "Exceptions"
private const val DESCRIPTION_DENIED =
    "Allow specific app/RP combos that were denied. Only enable apps you recognise."
private const val BUILT_IN_EXCEPTION_MESSAGE = "In community mode, built-in exceptions can't be removed."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExceptionsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(TITLE_EXCEPTIONS) },
        navigationIcon = {
            BackButton(onBack)
        },
    )
}

@Composable
fun ExceptionsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state as? MainUiState.Ready ?: return
    Scaffold(topBar = { ExceptionsTopBar(onBack) }) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
        ) {
            val nativeApps = uiState.nativeApps
            if (nativeApps !is Stored.Available) {
                LoadErrorCard(modifier = Modifier.fillMaxSize())
                return@Column
            }
            val denied =
                nativeApps.value.denied.entries
                    .sortedByDescending { it.value.lastDeniedAt }
            if (denied.isNotEmpty()) {
                SwipeSection(SECTION_TITLE_DENIED, DESCRIPTION_DENIED) {
                    denied.forEach { (appKey, info) ->
                        key(appKey) {
                            DeniedAppRow(
                                appKey = appKey,
                                info = info,
                                onApprove = { viewModel.applyNativeAppException(it, NativeAppExceptionAction.APPROVE) },
                                onForget = { viewModel.applyNativeAppException(it, NativeAppExceptionAction.DISMISS) },
                            )
                        }
                    }
                }
            }
            val exceptions = nativeApps.value.exceptionKeys()
            val userKeys = exceptions.toSet()
            val builtIn =
                (uiState.settings.nativeAppTrust as? NativeAppTrust.Community)
                    ?.assetLinksByDomain
                    ?.toExceptionKeys()
                    .orEmpty()
                    .filterNot { it in userKeys }
            if (exceptions.isNotEmpty() || builtIn.isNotEmpty()) {
                SwipeSection(SECTION_TITLE_EXCEPTIONS) {
                    exceptions.forEach { exceptionKey ->
                        key(exceptionKey) {
                            ExceptionRow(exceptionKey, builtIn = false, onMessage = viewModel.reportError) {
                                viewModel.applyNativeAppException(it, NativeAppExceptionAction.REVOKE)
                            }
                        }
                    }
                    builtIn.forEach { builtInKey ->
                        key(builtInKey) { ExceptionRow(builtInKey, builtIn = true, onMessage = viewModel.reportError) {} }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        content()
    }
}

@Composable
private fun appIcon(packageName: PackageName): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            context.packageManager
                .getApplicationIcon(packageName.value)
                .toBitmap()
                .asImageBitmap()
        }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeniedAppRow(
    appKey: DeniedNativeAppKey,
    info: DeniedNativeAppInfo,
    onApprove: (DeniedNativeAppKey) -> Unit,
    onForget: (DeniedNativeAppKey) -> Unit,
) {
    val formatted =
        remember(info.lastDeniedAt) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(info.lastDeniedAt))
        }
    val times = if (info.denialCount == 1) "time" else "times"
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onApprove(appKey)
                SwipeToDismissBoxValue.EndToStart -> onForget(appKey)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = { SwipeBackground(dismissState) },
    ) {
        ExceptionCard(
            packageName = appKey.packageName,
            supporting = "${appKey.rpId.value}: denied ${info.denialCount} $times, last $formatted",
            details = appKey.certFingerprints.map { it.colonHex }.sorted(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExceptionRow(
    exceptionKey: DeniedNativeAppKey,
    builtIn: Boolean,
    onMessage: (String) -> Unit,
    onRevoke: (DeniedNativeAppKey) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            when {
                direction != SwipeToDismissBoxValue.EndToStart -> {}

                builtIn -> {
                    onMessage(BUILT_IN_EXCEPTION_MESSAGE)
                    scope.launch { dismissState.reset() }
                }

                else -> {
                    onRevoke(exceptionKey)
                }
            }
        },
        backgroundContent = { SwipeBackground(dismissState) },
    ) {
        ExceptionCard(
            packageName = exceptionKey.packageName,
            supporting = exceptionKey.rpId.value,
            details = exceptionKey.certFingerprints.map { it.colonHex }.sorted(),
        )
    }
}

private val APP_ICON_SIZE = 40.dp

@Composable
private fun ExceptionCard(
    packageName: PackageName,
    supporting: String,
    details: List<String> = emptyList(),
) {
    val icon = appIcon(packageName)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        ListItem(
            leadingContent =
                icon?.let { bitmap ->
                    { Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(APP_ICON_SIZE)) }
                },
            supportingContent = {
                Column {
                    Text(supporting, style = MaterialTheme.typography.bodySmall)
                    details.forEach { detail ->
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) {
            val context = LocalContext.current
            val label = remember(packageName) { context.appLabel(packageName) }
            Text(if (label == packageName.value) label else "$label (${packageName.value})")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(dismissState: SwipeToDismissBoxState) {
    when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.EndToStart -> {
            SwipeActionBackground(
                dismissState = dismissState,
                arrangement = Arrangement.End,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                icon = Icons.Default.Delete,
            )
        }

        SwipeToDismissBoxValue.StartToEnd -> {
            SwipeActionBackground(
                dismissState = dismissState,
                arrangement = Arrangement.Start,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = Icons.Default.Add,
            )
        }

        SwipeToDismissBoxValue.Settled -> {}
    }
}
