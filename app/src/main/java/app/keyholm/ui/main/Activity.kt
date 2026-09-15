package app.keyholm.ui.main

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.keyholm.store.MigrationExportEntry
import app.keyholm.store.MigrationImport
import app.keyholm.store.migrationImportEntries
import app.keyholm.store.migrationImportResultMessage
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.rememberCryptoPrompt
import app.keyholm.ui.main.settings.ExceptionsScreen
import app.keyholm.ui.main.settings.SettingsScreen
import app.keyholm.ui.theme.KeyholmTheme
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data object MainRoute : NavKey

@Serializable
private data object SettingsRoute : NavKey

@Serializable
private data object HelpRoute : NavKey

@Serializable
private data object ExceptionsRoute : NavKey

@Serializable
private data class PasskeyDetailsRoute(
    val credentialId: CredentialId,
) : NavKey

// Material "shared axis X" motion spec: https://m2.material.io/design/motion/the-motion-system.html
private const val SHARED_AXIS_SLIDE_MS = 300
private const val SHARED_AXIS_ENTER_FADE_MS = 210
private const val SHARED_AXIS_ENTER_FADE_DELAY_MS = 90
private const val SHARED_AXIS_EXIT_FADE_MS = 90
private val SHARED_AXIS_OFFSET = 30.dp

private fun sharedAxisEnter(
    density: Density,
    reverse: Boolean = false,
): EnterTransition {
    val offsetPx = with(density) { SHARED_AXIS_OFFSET.roundToPx() }.let { if (reverse) -it else it }
    return fadeIn(tween(SHARED_AXIS_ENTER_FADE_MS, SHARED_AXIS_ENTER_FADE_DELAY_MS, LinearOutSlowInEasing)) +
        slideInHorizontally(tween(SHARED_AXIS_SLIDE_MS)) { offsetPx }
}

private fun sharedAxisExit(
    density: Density,
    reverse: Boolean = false,
): ExitTransition {
    val offsetPx = with(density) { SHARED_AXIS_OFFSET.roundToPx() }.let { if (reverse) it else -it }
    return fadeOut(tween(SHARED_AXIS_EXIT_FADE_MS, easing = FastOutLinearInEasing)) +
        slideOutHorizontally(tween(SHARED_AXIS_SLIDE_MS)) { offsetPx }
}

@Composable
private fun MainNavDisplay(
    viewModel: MainViewModel,
    cryptoPrompt: CryptoPrompt,
    backStack: NavBackStack<NavKey>,
    density: Density,
    snackbarHostState: SnackbarHostState,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = {
            sharedAxisEnter(density).togetherWith(sharedAxisExit(density))
        },
        popTransitionSpec = {
            sharedAxisEnter(density, reverse = true).togetherWith(sharedAxisExit(density, reverse = true))
        },
        predictivePopTransitionSpec = {
            sharedAxisEnter(density, reverse = true).togetherWith(sharedAxisExit(density, reverse = true))
        },
        entryProvider = mainEntryProvider(viewModel, cryptoPrompt, backStack, snackbarHostState),
    )
}

private fun mainEntryProvider(
    viewModel: MainViewModel,
    cryptoPrompt: CryptoPrompt,
    backStack: NavBackStack<NavKey>,
    snackbarHostState: SnackbarHostState,
) = entryProvider {
    entry<MainRoute> {
        MainScreen(
            viewModel = viewModel,
            cryptoPrompt = cryptoPrompt,
            snackbarHostState = snackbarHostState,
            onOpenSettings = { backStack.add(SettingsRoute) },
            onOpenDetails = { record -> backStack.add(PasskeyDetailsRoute(record.credentialId)) },
        )
    }
    entry<SettingsRoute> {
        SettingsScreen(
            viewModel = viewModel,
            cryptoPrompt = cryptoPrompt,
            onBack = { backStack.removeLastOrNull() },
            onOpenHelp = { backStack.add(HelpRoute) },
            onOpenExceptions = { backStack.add(ExceptionsRoute) },
        )
    }
    entry<HelpRoute> {
        HelpScreen(onBack = { backStack.removeLastOrNull() })
    }
    entry<ExceptionsRoute> {
        ExceptionsScreen(
            viewModel = viewModel,
            onBack = { backStack.removeLastOrNull() },
        )
    }
    entry<PasskeyDetailsRoute> { route ->
        PasskeyDetailsScreen(
            credentialId = route.credentialId,
            viewModel = viewModel,
            onBack = { backStack.removeLastOrNull() },
        )
    }
}

@Composable
private fun MigrationImportReview(
    review: ImportReview,
    preferRpName: Boolean,
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val iconPack by viewModel.iconPacks.pack.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    MigrationImportReviewScreen(
        entries = review.entries,
        preferRpName = preferRpName,
        iconPack = iconPack,
        onConfirm = {
            viewModel.imports.importList(
                entries = review.entries,
                onResult = { result ->
                    val message = migrationImportResultMessage(result)
                    scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long) }
                },
                onFailure = {
                    viewModel.reportError(ErrorMessages.CHECK_EXISTING_FAILED)
                },
            )
            viewModel.imports.dismiss()
        },
        onCancel = {
            when (review) {
                is ImportReview.DeepLink -> activity?.finish()
                is ImportReview.InApp -> viewModel.imports.dismiss()
            }
        },
    )
}

@Composable
private fun MainContent(importEntries: List<MigrationExportEntry>) {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importReview by viewModel.imports.review.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(MainRoute)
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(importEntries) {
        if (importEntries.isNotEmpty()) viewModel.imports.request(ImportReview.DeepLink(importEntries))
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.lockIfIdle() }

    // Launchers must be registered unconditionally
    val cryptoPrompt = rememberCryptoPrompt()

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val review = importReview
            val ready = uiState as? MainUiState.Ready
            if (ready?.locked == true) {
                LockScreen(viewModel, cryptoPrompt)
            } else if (review != null && ready != null) {
                MigrationImportReview(review, ready.settings.preferRpName, viewModel, snackbarHostState)
            } else {
                MainNavDisplay(viewModel, cryptoPrompt, backStack, density, snackbarHostState)
            }
        }
        SnackbarHost(
            snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(ScaffoldDefaults.contentWindowInsets),
        )
    }
}

class Activity : FragmentActivity() {
    private val log = logger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        val importEntries =
            when (
                val import =
                    intent?.data?.let { migrationImportEntries(log, it) } ?: MigrationImport.NotAMigrationLink
            ) {
                is MigrationImport.Entries -> {
                    import.entries
                }

                MigrationImport.NotAMigrationLink -> {
                    emptyList()
                }

                MigrationImport.Malformed -> {
                    log.e { "the migration deep link is malformed" }
                    Toast.makeText(this, ErrorMessages.MIGRATION_LINK_MALFORMED, Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            }
        setContent {
            KeyholmTheme {
                MainContent(importEntries)
            }
        }
    }
}
