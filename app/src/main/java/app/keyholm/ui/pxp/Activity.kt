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
import app.keyholm.pxp.PxpFailure
import app.keyholm.pxp.PxpStage
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
                viewModel.setupFailed(PxpFailure.PermissionsDenied(denied.toList()))
            }
        }

    private val requestEnable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                start(intent)
            } else {
                viewModel.setupFailed(PxpFailure.BluetoothNotEnabled)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeyholmTheme {
                val status by viewModel.status.collectAsStateWithLifecycle()
                val confirmationCode by viewModel.confirmationCode.collectAsStateWithLifecycle()
                status?.let { current ->
                    if (current.failure == null && current.stage in setOf(PxpStage.ChannelClosed, PxpStage.ClientShutdown)) {
                        LaunchedEffect(Unit) { finish() }
                    }
                    PxpStatusSheet(current, confirmationCode, onClose = ::finish)
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
                viewModel.stop()
                viewModel.setupFailed(PxpFailure.InvalidQrCode(decoded.exceptionOrNull()))
            }

            PxpChannel.BLE !in payload.channels -> {
                viewModel.stop()
                viewModel.setupFailed(PxpFailure.NoBleChannel)
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
