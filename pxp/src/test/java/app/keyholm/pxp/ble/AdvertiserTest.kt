package app.keyholm.pxp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import android.os.ParcelUuid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SERVICE_UUID = ParcelUuid(UUID.fromString("0000fff9-0000-1000-8000-00805f9b34fb"))
private val SERVICE_DATA = byteArrayOf(1, 2, 3, 4)

@RunWith(RobolectricTestRunner::class)
class AdvertiserTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

    private suspend fun failureOf(): Throwable? = runCatching { advertise(context, SERVICE_DATA).first() }.exceptionOrNull()

    // Advertising callbacks are posted to the paused main looper.
    private suspend fun idleUntil(condition: () -> Boolean) =
        withTimeout(5.seconds) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10.milliseconds)
            }
        }

    @Test
    fun `fails when bluetooth is off`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(false)

            assertThat(failureOf()).isInstanceOf(AdvertisingFailure::class.java)
        }

    @Test
    fun `fails without extended advertising`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(true)
            shadowOf(adapter).setIsLeExtendedAdvertisingSupported(false)

            assertThat(failureOf()).isInstanceOf(AdvertisingFailure::class.java)
        }

    @Test
    fun `advertises the service data until cancelled`() =
        runBlocking<Unit> {
            shadowOf(adapter).setEnabled(true)
            val advertiser = shadowOf(adapter.bluetoothLeAdvertiser)
            val states = mutableListOf<AdvertisingState>()

            val advertising =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    advertise(context, SERVICE_DATA).collect { states.add(it) }
                }
            idleUntil { states.isNotEmpty() }

            assertThat(states.single()).isInstanceOf(AdvertisingState.Started::class.java)
            assertThat(advertiser.advertisingSetRequestCount).isEqualTo(1)
            val data = advertiser.lastAdvertisingData
            assertThat(data.serviceUuids).containsExactly(SERVICE_UUID)
            assertThat(data.serviceData[SERVICE_UUID]).isEqualTo(SERVICE_DATA)

            advertising.cancelAndJoin()
            idleUntil { advertiser.advertisingSetRequestCount == 0 }
        }
}
