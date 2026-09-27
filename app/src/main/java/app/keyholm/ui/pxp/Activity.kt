package app.keyholm.ui.pxp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.PxpStatus
import app.keyholm.pxp.qr.PxpChannel
import app.keyholm.pxp.qr.PxpQrPayload
import app.keyholm.ui.theme.KeyholmTheme

class Activity : FragmentActivity() {
    private val viewModel: PxpViewModel by viewModels()
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isEmpty()) {
                start(intent)
            } else {
                val names = denied.joinToString { it.substringAfterLast('.') }
                viewModel.setupFailed("not granted: $names")
            }
        }

    private val requestEnable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                start(intent)
            } else {
                viewModel.setupFailed("bluetooth was not turned on")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeyholmTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    PxpState.Idle -> {}

                    is PxpState.Running -> {
                        val status by current.ceremony.status.collectAsStateWithLifecycle()
                        if (status.failure == null && status.stage in setOf(PxpStage.ChannelClosed, PxpStage.ClientShutdown)) {
                            LaunchedEffect(Unit) { finish() }
                        }
                        PxpStatusSheet(
                            status,
                            current.ceremony.confirmationCode,
                            onClose = ::finish,
                        )
                    }

                    is PxpState.SetupFailed -> {
                        PxpStatusSheet(
                            PxpStatus(PxpStage.EstablishingProximity, failure = current.message),
                            confirmationCode = null,
                            onClose = ::finish,
                        )
                    }
                }
            }
        }
        when {
            savedInstanceState == null -> start(intent)
            !viewModel.intentHandled -> finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent?) {
        viewModel.intentHandled = true
        val decoded = PxpQrPayload.decode(intent?.dataString.orEmpty())
        val payload = decoded.getOrNull()
        val missing =
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            payload == null -> {
                val message = "the QR code did not parse: ${decoded.exceptionOrNull()?.message}"
                viewModel.stop()
                viewModel.setupFailed(message)
            }

            PxpChannel.BLE !in payload.channels -> {
                viewModel.stop()
                viewModel.setupFailed("the QR code doesn't offer BLE")
            }

            missing.isNotEmpty() -> {
                viewModel.stop()
                requestPermissions.launch(missing.toTypedArray())
            }

            adapter?.isEnabled == false -> {
                viewModel.stop()
                requestEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }

            else -> {
                viewModel.start(payload)
            }
        }
    }
}
