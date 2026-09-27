package app.keyholm.pxp.shadows

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothServerSocket
import java.io.IOException

private const val ACCEPT_POLL_MILLIS = 50

@Implements(BluetoothAdapter::class)
class RecordingBluetoothAdapter : ShadowBluetoothAdapter() {
    @Volatile
    var l2capServerSocket: BluetoothServerSocket? = null

    @Implementation
    override fun listenUsingInsecureL2capChannel(): BluetoothServerSocket =
        super.listenUsingInsecureL2capChannel().also { l2capServerSocket = it }
}

// Like the platform, closing the server unblocks a pending accept.
@Implements(BluetoothServerSocket::class)
class ClosableBluetoothServerSocket : ShadowBluetoothServerSocket() {
    @Volatile
    private var closed = false

    @Implementation
    override fun accept(timeout: Int): BluetoothSocket {
        while (true) {
            try {
                return super.accept(ACCEPT_POLL_MILLIS)
            } catch (e: IOException) {
                if (closed) throw e
            }
        }
    }

    @Implementation
    override fun close() {
        closed = true
        super.close()
    }
}
