package io.rebble.libpebblecommon.connection.bt.ble

import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import kotlin.time.Duration

data class BlePlatformConfig(
    val pinAddress: Boolean = true,
    val phoneRequestsPairing: Boolean = true,
    val writeConnectivityTrigger: Boolean = true,
    val initialMtu: Int = DEFAULT_MTU,
    val desiredTxWindow: Int = MAX_TX_WINDOW,
    val desiredRxWindow: Int = MAX_RX_WINDOW,
    val useNativeMtu: Boolean = true,
    val sendPpogResetOnDisconnection: Boolean = false,
    val delayBleConnectionsAfterAppStart: Boolean = false,
    val delayBleDisconnections: Boolean = true,
    val fallbackToResetRequest: Boolean = false,
    val closeGattServerWhenBtDisabled: Boolean = true,
    val supportsBtClassic: Boolean = false,
    val supportsPpogResetCharacteristic: Boolean = false,
    val supportsGattAutoConnect: Boolean = false,
    /** Writing true tells the watch's firmware to never request a connection-parameter change
     *  itself again ("Pebble will never request a connection parameter change") - fine on
     *  platforms whose OS/app then actually manages priority (Android does), but on a platform
     *  that never calls the equivalent of requestConnectionPriority, it just permanently disables
     *  the watch's own perfectly good ResponseTime state machine for nothing: stuck at whatever
     *  the kernel picked at connect, no fast mode for bulk transfers, no slow/low-power mode when
     *  idle. */
    val phoneManagesConnectionParams: Boolean = true,
    /** iOS bluetoothd can stall the write-without-response readiness signal for
     *  ~5s (MOB-9394), wedging Kable's write() before any bytes are dispatched.
     *  When set, WithoutResponse writes time out after this and re-issue. */
    val writeWithoutResponseStallTimeout: Duration? = null,
)