package app.keyholm.ui.main

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyholm.iconpack.IconPackController
import app.keyholm.iconpack.IconPackStorage
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.provider.isCredentialProviderEnabled
import app.keyholm.provider.setCredentialProviderComponentEnabled
import app.keyholm.settings.Settings
import app.keyholm.settings.SettingsController
import app.keyholm.settings.SettingsRepository
import app.keyholm.settings.deleteSettingsStoreFile
import app.keyholm.store.DeniedNativeAppKey
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.DeniedNativeApps
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.deleteDeniedNativeAppStoreFile
import app.keyholm.store.deleteMigrationStoreFile
import app.keyholm.store.deletePasskeyStoreFile
import app.keyholm.store.writeStore
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.util.logger
import app.keyholm.webauthn.WebAuthnAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STATE_STOP_TIMEOUT_MS = 5_000L

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data object Failed : Loadable<Nothing>

    data class Loaded<T>(
        val value: T,
    ) : Loadable<T>
}

sealed interface Stored<out T> {
    data class Available<T>(
        val value: T,
    ) : Stored<T>

    data object Unavailable : Stored<Nothing>
}

data class DeviceStatus(
    val secureElement: Boolean,
    val tee: Boolean,
    val teeMlDsa: Boolean,
    val providerEnabled: Boolean,
)

enum class NativeAppExceptionAction { APPROVE, DISMISS, REVOKE }

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(
        val locked: Boolean,
        val device: DeviceStatus,
        val passkeys: Stored<List<PasskeyRecord>>,
        val migrationPlaceholders: Stored<List<MigrationPlaceholder>>,
        val nativeApps: Stored<DeniedNativeApps>,
        val details: PasskeyDetails,
        val settings: Settings,
        val pendingDeleteBatches: PendingDeleteBatches,
    ) : MainUiState
}

data class PendingDeleteBatches(
    val passkeys: List<PasskeyRecord>,
    val placeholders: List<MigrationPlaceholder>,
)

private data class SettingsState(
    val settings: Settings,
    val locked: Boolean,
)

private data class StoresState(
    val passkeys: Stored<List<PasskeyRecord>>,
    val placeholders: Stored<List<MigrationPlaceholder>>,
    val nativeApps: Stored<DeniedNativeApps>,
)

class MainViewModel internal constructor(
    application: Application,
    private val dispatcher: CoroutineDispatcher,
    renderDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.IO, Dispatchers.Default)

    private val log = logger()
    private val settingsRepo = SettingsRepository(application)
    private val passkeyRepo = PasskeyRepository(application)
    private val migrationRepo = MigrationRepository(application)
    private val deniedAppsRepo = DeniedNativeAppRepository(application)
    private val iconPackStorage = IconPackStorage(application, dispatcher, renderDispatcher)

    val errors: Flow<String>

    val reportError: (String) -> Unit

    init {
        val actionErrors = Channel<String>(Channel.BUFFERED)
        errors = actionErrors.receiveAsFlow()
        reportError = { actionErrors.trySend(it) }
    }

    private val refreshTicks = MutableStateFlow(0)

    private val deviceStatus: Flow<DeviceStatus> =
        refreshTicks.map {
            withContext(dispatcher) {
                val context = getApplication<Application>()
                val tee = SecureKeyManager.isTeeAvailable(context)
                setCredentialProviderComponentEnabled(context, tee)
                DeviceStatus(
                    secureElement = SecureKeyManager.isSecureElementAvailable(context),
                    tee = tee,
                    teeMlDsa = SecureKeyManager.isAlgorithmAvailable(context, WebAuthnAlgorithm.ML_DSA_87),
                    providerEnabled = isCredentialProviderEnabled(context),
                )
            }
        }

    private fun <T> Flow<T>.stored(): Flow<Stored<T>> =
        map<T, Stored<T>> { Stored.Available(it) }.catch { e ->
            log.e(e) { "store read failed" }
            emit(Stored.Unavailable)
        }

    private val storesState: Flow<StoresState> =
        combine(
            passkeyRepo.passkeys.map { it.asReversed() }.stored(),
            migrationRepo.placeholders.stored(),
            deniedAppsRepo.nativeApps.stored(),
        ) { passkeys, placeholders, nativeApps ->
            StoresState(passkeys, placeholders, nativeApps)
        }

    private val unlocked = MutableStateFlow(false)
    private var unlocking = false

    suspend fun unlockWith(authenticate: suspend () -> Boolean) {
        unlocking = true
        try {
            unlocked.value = authenticate()
        } finally {
            unlocking = false
        }
    }

    fun lockIfIdle() {
        if (!unlocking) unlocked.value = false
    }

    private val settingsState: Flow<SettingsState> =
        combine(settingsRepo.settings, unlocked) { settings, unlocked ->
            SettingsState(settings, locked = settings.appLock && !unlocked)
        }

    val pendingDeletes =
        PendingDeleteController(PasskeyPendingDeletes(passkeyRepo), viewModelScope, dispatcher, reportError)

    val placeholderDeletes =
        PendingDeleteController(PlaceholderPendingDeletes(migrationRepo), viewModelScope, dispatcher, reportError)

    val details = PasskeyDetailsController(application, viewModelScope, dispatcher)

    val uiState: StateFlow<MainUiState> =
        combine(
            settingsState,
            storesState,
            deviceStatus,
            details.state,
            combine(pendingDeletes.batch, placeholderDeletes.batch, ::PendingDeleteBatches),
        ) { settingsState, stores, device, openDetails, pendingDeleteBatches ->
            MainUiState.Ready(
                locked = settingsState.locked,
                device = device,
                passkeys = stores.passkeys,
                migrationPlaceholders = stores.placeholders,
                nativeApps = stores.nativeApps,
                details = openDetails,
                settings = settingsState.settings,
                pendingDeleteBatches = pendingDeleteBatches,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            MainUiState.Loading,
        )

    val settings =
        SettingsController(settingsRepo, viewModelScope, deniedAppsRepo, reportError)

    val imports = ImportReviewController(migrationRepo, passkeyRepo, viewModelScope)

    val iconPacks = IconPackController(iconPackStorage, viewModelScope, reportError).also(::addCloseable)

    init {
        pendingDeletes.resume()
        placeholderDeletes.resume()
    }

    fun refresh() {
        refreshTicks.update { it + 1 }
    }

    fun resetEverything() {
        pendingDeletes.clear()
        placeholderDeletes.clear()
        viewModelScope.launch {
            withContext(dispatcher) { SecureKeyManager().deleteAllKeys() }
            val failure =
                listOf(
                    passkeyRepo.saveAll(emptyList()),
                    migrationRepo.saveAll(emptyList()),
                    deniedAppsRepo.clear(),
                    settingsRepo.reset(),
                ).firstNotNullOfOrNull { it.exceptionOrNull() }
            if (failure != null) {
                log.e(failure) { "reset failed, deleting the store files" }
                val deleted = withContext(dispatcher) { deleteStoreFiles(getApplication<Application>()) }
                if (!deleted) {
                    reportError(ErrorMessages.UPDATE_FAILED)
                    return@launch
                }
            }
            refresh()
        }
    }

    private fun deleteStoreFiles(context: Context): Boolean =
        listOf(
            deletePasskeyStoreFile(context),
            deleteMigrationStoreFile(context),
            deleteDeniedNativeAppStoreFile(context),
            deleteSettingsStoreFile(context),
        ).all { it }

    fun setRpName(
        record: PasskeyRecord,
        name: String,
    ) {
        viewModelScope.launch {
            if (!writeStore { passkeyRepo.setRpName(record.credentialId, name) }) {
                reportError(ErrorMessages.UPDATE_FAILED)
            }
        }
    }

    fun setUser(
        record: PasskeyRecord,
        name: String,
        displayName: String,
    ) {
        viewModelScope.launch {
            if (!writeStore { passkeyRepo.setUser(record.credentialId, name, displayName) }) {
                reportError(ErrorMessages.UPDATE_FAILED)
            }
        }
    }

    fun applyNativeAppException(
        key: DeniedNativeAppKey,
        action: NativeAppExceptionAction,
    ) {
        viewModelScope.launch {
            val applied =
                writeStore {
                    when (action) {
                        NativeAppExceptionAction.APPROVE -> deniedAppsRepo.accept(key)
                        NativeAppExceptionAction.DISMISS -> deniedAppsRepo.dismiss(key)
                        NativeAppExceptionAction.REVOKE -> deniedAppsRepo.revoke(key)
                    }
                }
            if (!applied) reportError(ErrorMessages.UPDATE_FAILED)
        }
    }
}
