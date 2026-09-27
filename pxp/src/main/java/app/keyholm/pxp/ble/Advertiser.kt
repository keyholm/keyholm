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

sealed interface AdvertisingState {
    data class Started(
        val txPower: Int,
    ) : AdvertisingState

    data object Stopped : AdvertisingState
}

class AdvertisingFailure(
    message: String,
) : Exception(message)

@RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
fun advertise(
    context: Context,
    serviceData: ByteArray,
): Flow<AdvertisingState> =
    callbackFlow {
        val advertiser =
            resolveAdvertiser(context).getOrElse {
                close(it)
                return@callbackFlow
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
                        close(AdvertisingFailure("advertising failed with status $status"))
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

private fun resolveAdvertiser(context: Context): Result<BluetoothLeAdvertiser> {
    val adapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.failure(AdvertisingFailure("no bluetooth adapter"))
    if (!adapter.isEnabled) {
        return Result.failure(AdvertisingFailure("bluetooth is off"))
    }
    if (!adapter.isLeExtendedAdvertisingSupported) {
        return Result.failure(AdvertisingFailure("no extended advertising support"))
    }
    val advertiser =
        adapter.bluetoothLeAdvertiser
            ?: return Result.failure(AdvertisingFailure("no BLE advertiser"))
    return Result.success(advertiser)
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
