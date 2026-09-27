package app.keyholm.pxp.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

// Static from spec
private val CABLE_SERVICE_UUID = ParcelUuid(UUID.fromString("0000fff9-0000-1000-8000-00805f9b34fb"))

sealed interface AdvertisingFailure {
    data object NoAdapter : AdvertisingFailure

    data object BluetoothOff : AdvertisingFailure

    data object NoExtendedAdvertising : AdvertisingFailure

    data object NoAdvertiser : AdvertisingFailure

    data class Failed(
        val status: Int,
    ) : AdvertisingFailure
}

sealed interface AdvertisingState {
    data class Started(
        val txPower: Int,
    ) : AdvertisingState

    data object Stopped : AdvertisingState

    data class Failed(
        val failure: AdvertisingFailure,
    ) : AdvertisingState
}

private sealed interface ResolvedAdvertiser {
    class Ready(
        val advertiser: BluetoothLeAdvertiser,
    ) : ResolvedAdvertiser

    class Unavailable(
        val failure: AdvertisingFailure,
    ) : ResolvedAdvertiser
}

@RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
fun advertise(
    context: Context,
    serviceData: ByteArray,
): Flow<AdvertisingState> =
    callbackFlow {
        val advertiser =
            when (val resolved = resolveAdvertiser(context)) {
                is ResolvedAdvertiser.Unavailable -> {
                    trySend(AdvertisingState.Failed(resolved.failure))
                    close()
                    return@callbackFlow
                }

                is ResolvedAdvertiser.Ready -> {
                    resolved.advertiser
                }
            }
        val callback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int,
                ) {
                    if (status == ADVERTISE_SUCCESS) {
                        trySend(AdvertisingState.Started(txPower))
                    } else {
                        trySend(AdvertisingState.Failed(AdvertisingFailure.Failed(status)))
                        close()
                    }
                }

                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                    trySend(AdvertisingState.Stopped)
                }
            }

        advertiser.startAdvertisingSet(
            advertisingParameters(),
            advertisingData(serviceData),
            null,
            null,
            null,
            callback,
        )
        awaitClose { advertiser.stopAdvertisingSet(callback) }
    }

private fun resolveAdvertiser(context: Context): ResolvedAdvertiser {
    val adapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ResolvedAdvertiser.Unavailable(AdvertisingFailure.NoAdapter)
    if (!adapter.isEnabled) {
        return ResolvedAdvertiser.Unavailable(AdvertisingFailure.BluetoothOff)
    }
    if (!adapter.isLeExtendedAdvertisingSupported) {
        return ResolvedAdvertiser.Unavailable(AdvertisingFailure.NoExtendedAdvertising)
    }
    val advertiser =
        adapter.bluetoothLeAdvertiser
            ?: return ResolvedAdvertiser.Unavailable(AdvertisingFailure.NoAdvertiser)
    return ResolvedAdvertiser.Ready(advertiser)
}

private fun advertisingParameters(): AdvertisingSetParameters =
    AdvertisingSetParameters
        .Builder()
        .setLegacyMode(false)
        .setConnectable(true)
        .setScannable(false)
        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        .build()

private fun advertisingData(serviceData: ByteArray): AdvertiseData =
    AdvertiseData
        .Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(CABLE_SERVICE_UUID)
        .addServiceData(CABLE_SERVICE_UUID, serviceData)
        .build()
