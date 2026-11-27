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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keyholm.iconpack.IconPack
import app.keyholm.pxp.PxpFailure
import app.keyholm.pxp.PxpStage
import app.keyholm.pxp.qr.PxpChannel
import app.keyholm.pxp.qr.PxpQrPayload
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.createpasskey.CreationOptionsSheet
import app.keyholm.ui.theme.KeyholmTheme

class Activity : FragmentActivity() {
    private val viewModel: PxpViewModel by viewModels()
    private val cryptoPrompt = CryptoPrompt(this)
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
                val internalFailure by viewModel.ui.failure.collectAsStateWithLifecycle()
                status?.let { current ->
                    val failed = current.failure != null || internalFailure != null
                    if (!failed && current.stage in setOf(PxpStage.ChannelClosed, PxpStage.ClientShutdown)) {
                        LaunchedEffect(Unit) { finish() }
                    }
                    PxpStatusSheet(current, cryptoPrompt, viewModel.ui, onClose = ::finish)
                }
                val iconPack by viewModel.iconPacks.pack.collectAsStateWithLifecycle()
                ChoiceSheets(viewModel.ui.choices, iconPack)
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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
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

@Composable
private fun ChoiceSheets(
    choices: ChoiceRelay,
    iconPack: IconPack?,
) {
    val creationOptions by choices.creationOptions.pending.collectAsStateWithLifecycle()
    creationOptions?.let { pending ->
        CreationOptionsSheet(
            pending.request,
            onChoice = { pending.response.complete(it) },
            onDismiss = { pending.response.complete(null) },
        )
    }
    val accountChoice by choices.accountChoice.pending.collectAsStateWithLifecycle()
    accountChoice?.let { pending ->
        AccountPickerSheet(
            pending.request,
            iconPack,
            onChoice = { pending.response.complete(it) },
            onDismiss = { pending.response.complete(null) },
        )
    }
}
