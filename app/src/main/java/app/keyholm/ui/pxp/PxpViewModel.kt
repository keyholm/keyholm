package app.keyholm.ui.pxp

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyholm.ctap.CtapAuthenticator
import app.keyholm.pxp.PxpCeremony
import app.keyholm.pxp.PxpFailure
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.PxpStatus
import app.keyholm.pxp.crypto.PxpNoise
import app.keyholm.pxp.qr.PxpQrPayload
import app.keyholm.settings.SettingsRepository
import app.keyholm.store.PasskeyRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PxpViewModel internal constructor(
    application: Application,
    private val dispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.IO)

    private val settingsRepo = SettingsRepository(application)
    private val passkeyRepo = PasskeyRepository(application)
    internal val ui = CtapUi()
    private var running: Job? = null
    var intentHandled = false
    private val _status = MutableStateFlow<PxpStatus?>(null)
    val status: StateFlow<PxpStatus?> = _status.asStateFlow()
    private val _confirmationCode = MutableStateFlow<String?>(null)
    val confirmationCode: StateFlow<String?> = _confirmationCode.asStateFlow()

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT],
    )
    fun start(payload: PxpQrPayload) {
        stop()
        running =
            viewModelScope.launch(dispatcher) {
                val app = getApplication<Application>()
                val authenticator =
                    CtapAuthenticator(app, settingsRepo.settings.first(), passkeyRepo, ui, dispatcher)
                val pxp = PxpCeremony(app, payload, authenticator, PxpNoise)
                _confirmationCode.value = pxp.confirmationCode
                pxp.status.onEach { _status.value = it }.launchIn(this)
                pxp.run()
            }
    }

    fun stop() {
        running?.cancel()
        ui.clear()
        _status.value = null
        _confirmationCode.value = null
    }

    fun setupFailed(failure: PxpFailure) {
        _status.value = PxpStatus(PxpStage.EstablishingProximity, failure = failure)
    }
}
