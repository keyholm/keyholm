package app.keyholm.ui.pxp

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyholm.ctap.CtapAuthenticator
import app.keyholm.pxp.PxpCeremony
import app.keyholm.pxp.crypto.PxpNoise
import app.keyholm.pxp.qr.PxpQrPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PxpViewModel internal constructor(
    application: Application,
    private val dispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.Default)

    private var running: Job? = null
    var intentHandled = false
    private val _state = MutableStateFlow<PxpState>(PxpState.Idle)
    val state: StateFlow<PxpState> = _state.asStateFlow()

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT],
    )
    fun start(payload: PxpQrPayload) {
        running?.cancel()
        val pxp = PxpCeremony(getApplication(), payload, CtapAuthenticator(), PxpNoise)
        _state.value = PxpState.Running(pxp)
        running = viewModelScope.launch(dispatcher) { pxp.run() }
    }

    fun stop() {
        running?.cancel()
        _state.value = PxpState.Idle
    }

    fun setupFailed(message: String) {
        _state.value = PxpState.SetupFailed(message)
    }
}

sealed interface PxpState {
    data object Idle : PxpState

    data class Running(
        val ceremony: PxpCeremony,
    ) : PxpState

    data class SetupFailed(
        val message: String,
    ) : PxpState
}
