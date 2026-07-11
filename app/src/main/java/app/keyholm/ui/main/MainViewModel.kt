package app.keyholm.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.provider.isCredentialProviderEnabled
import app.keyholm.provider.setCredentialProviderComponentEnabled
import app.keyholm.settings.Settings
import app.keyholm.settings.SettingsController
import app.keyholm.settings.SettingsRepository
import app.keyholm.store.DeniedNativeAppKey
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.DeniedNativeApps
import app.keyholm.store.MigrationPlaceholder
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.writeStore
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
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

data class AttestationInfo(
    val pemCerts: List<String>,
)

sealed interface KeyAuthenticators {
    val mainKey: AuthenticatorPolicy

    data class SigningOnly(
        override val mainKey: AuthenticatorPolicy,
    ) : KeyAuthenticators

    data class WithPrf(
        override val mainKey: AuthenticatorPolicy,
        val prfKey: AuthenticatorPolicy,
    ) : KeyAuthenticators
}

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

sealed interface PasskeyDetails {
    data object Closed : PasskeyDetails

    data class Open(
        val credentialId: CredentialId,
        val attestation: Loadable<AttestationInfo>,
        val authenticators: Loadable<KeyAuthenticators>,
    ) : PasskeyDetails
}

data class DeviceStatus(
    val secureElement: Boolean,
    val tee: Boolean,
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
        val pendingDeleteBatch: List<PasskeyRecord>,
    ) : MainUiState
}

private data class SettingsState(
    val settings: Settings,
    val locked: Boolean,
)

private data class StoresState(
    val passkeys: Stored<List<PasskeyRecord>>,
    val placeholders: Stored<List<MigrationPlaceholder>>,
    val nativeApps: Stored<DeniedNativeApps>,
)

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val log = logger()
    private val settingsRepo = SettingsRepository(application)
    private val passkeyRepo = PasskeyRepository(application)
    private val migrationRepo = MigrationRepository(application)
    private val deniedAppsRepo = DeniedNativeAppRepository(application)

    private val actionErrors = Channel<String>(Channel.BUFFERED)

    /** One-shot failures for an action the user took, shown as a snackbar. */
    val errors: Flow<String> = actionErrors.receiveAsFlow()

    private val refreshTicks = MutableStateFlow(0)
    private val details = MutableStateFlow<PasskeyDetails>(PasskeyDetails.Closed)

    private val deviceStatus: Flow<DeviceStatus> =
        refreshTicks.map {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val secureElement = SecureKeyManager.isSecureElementAvailable(context)
                setCredentialProviderComponentEnabled(context, secureElement)
                DeviceStatus(
                    secureElement = secureElement,
                    tee = SecureKeyManager.isTeeAvailable(context),
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
        PendingDeleteController(passkeyRepo, viewModelScope) { actionErrors.trySend(it) }

    val uiState: StateFlow<MainUiState> =
        combine(
            settingsState,
            storesState,
            deviceStatus,
            details,
            pendingDeletes.batch,
        ) { settingsState, stores, device, openDetails, pendingDeleteBatch ->
            MainUiState.Ready(
                locked = settingsState.locked,
                device = device,
                passkeys = stores.passkeys,
                migrationPlaceholders = stores.placeholders,
                nativeApps = stores.nativeApps,
                details = openDetails,
                settings = settingsState.settings,
                pendingDeleteBatch = pendingDeleteBatch,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            MainUiState.Loading,
        )

    val settings =
        SettingsController(settingsRepo, viewModelScope, deniedAppsRepo) { actionErrors.trySend(it) }

    val imports = ImportReviewController(migrationRepo, passkeyRepo, viewModelScope)

    init {
        pendingDeletes.resume()
    }

    fun refresh() {
        refreshTicks.update { it + 1 }
    }

    fun loadPasskeyInfo(record: PasskeyRecord) {
        details.value = PasskeyDetails.Open(record.credentialId, Loadable.Loading, Loadable.Loading)
        viewModelScope.launch {
            val pem =
                withContext(Dispatchers.IO) {
                    runCatching { SecureKeyManager().certificateChainPem(record.keyAlias) }
                }
            val attestation: Loadable<AttestationInfo> =
                pem.fold(
                    onSuccess = { Loadable.Loaded(AttestationInfo(it)) },
                    onFailure = { e ->
                        log.e(e) { "couldn't read the certificate chain" }
                        Loadable.Failed
                    },
                )
            updateOpenDetails(record.credentialId) { it.copy(attestation = attestation) }
        }
        viewModelScope.launch {
            val read =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val keyManager = SecureKeyManager()
                        val mainKey =
                            keyManager.allowedAuthenticatorsFor(record.keyAlias, record.keystore.coseAlgorithm)
                        if (record.hasPrf) {
                            KeyAuthenticators.WithPrf(mainKey, keyManager.allowedAuthenticatorsForHmac(record.hmacKeyAlias))
                        } else {
                            KeyAuthenticators.SigningOnly(mainKey)
                        }
                    }
                }
            val authenticators: Loadable<KeyAuthenticators> =
                read.fold(
                    onSuccess = { Loadable.Loaded(it) },
                    onFailure = { e ->
                        log.e(e) { "couldn't read the key authenticators" }
                        Loadable.Failed
                    },
                )
            updateOpenDetails(record.credentialId) { it.copy(authenticators = authenticators) }
        }
    }

    private fun updateOpenDetails(
        credentialId: CredentialId,
        transform: (PasskeyDetails.Open) -> PasskeyDetails.Open,
    ) {
        details.update { current ->
            if (current is PasskeyDetails.Open && current.credentialId == credentialId) transform(current) else current
        }
    }

    fun clearPasskeyInfo() {
        details.value = PasskeyDetails.Closed
    }

    fun resetEverything() {
        pendingDeletes.clear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SecureKeyManager().deleteAllKeys() }
            val failure =
                listOf(
                    passkeyRepo.saveAll(emptyList()),
                    migrationRepo.saveAll(emptyList()),
                    deniedAppsRepo.clear(),
                    settingsRepo.reset(),
                ).firstNotNullOfOrNull { it.exceptionOrNull() }
            if (failure != null) {
                log.e(failure) { "reset failed" }
                actionErrors.trySend(ErrorMessages.UPDATE_FAILED)
                return@launch
            }
            refresh()
        }
    }

    fun dismissMigrationPlaceholder(placeholder: MigrationPlaceholder) {
        viewModelScope.launch {
            if (!writeStore { migrationRepo.delete(placeholder.rpId, placeholder.userName) }) {
                actionErrors.trySend(ErrorMessages.UPDATE_FAILED)
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
            if (!applied) actionErrors.trySend(ErrorMessages.UPDATE_FAILED)
        }
    }
}
