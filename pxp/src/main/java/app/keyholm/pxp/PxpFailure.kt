package app.keyholm.pxp

import app.keyholm.pxp.ble.AdvertisingFailure
import app.keyholm.pxp.ble.L2capFailure
import kotlin.time.Duration

sealed interface PxpFailure {
    data class PermissionsDenied(
        val permissions: List<String>,
    ) : PxpFailure

    data object BluetoothNotEnabled : PxpFailure

    data class InvalidQrCode(
        val cause: Throwable?,
    ) : PxpFailure

    data object NoBleChannel : PxpFailure

    data class Listening(
        val failure: L2capFailure,
    ) : PxpFailure

    data class Advertising(
        val failure: AdvertisingFailure,
    ) : PxpFailure

    data class Accepting(
        val cause: Throwable,
    ) : PxpFailure

    data class Timeout(
        val after: Duration,
    ) : PxpFailure

    data class Sending(
        val cause: Throwable,
    ) : PxpFailure

    data class Receiving(
        val cause: Throwable,
    ) : PxpFailure

    data object EmptyTunnelMessage : PxpFailure

    data object EmptyCtapMessage : PxpFailure
}
