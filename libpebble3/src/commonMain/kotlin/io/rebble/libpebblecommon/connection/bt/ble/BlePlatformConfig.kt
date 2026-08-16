package io.rebble.libpebblecommon.connection.bt.ble

import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    /** When reversed-PPoG subscribe setup fails, whether to fall back to hosting forward PPoG
     *  for that connection. Correct on iOS, where a stale cached GATT service is the usual cause
     *  and CoreBluetooth gives no way to force re-discovery. Wrong on a platform where forward is
     *  the fragile path and the watch genuinely hosts the reversed service - there, failing the
     *  connect and letting the normal retry loop re-run reversed setup fresh is the better
     *  reaction. */
    val fallbackToForwardPpogOnReversedSetupFailure: Boolean = true,
    /** How long the phone waits for a firmware ack before retransmitting a PPoG packet. Firmware
     *  acks within ~200ms normally and times out waiting for one after 5-6s (2 ticks of
     *  PPOGATT_TIMEOUT_TICK_INTERVAL_SECS=2 x PPOGATT_TIMEOUT_TICKS=3), at which point it starts
     *  resetting the session itself - a phone-side value at or above that means the firmware's
     *  own timeout always fires first, and the phone's retransmit races a session already being
     *  torn down. Default kept at the original 10s pending mobile soak-testing of a shorter
     *  value; set lower on platforms where it's been verified safe. */
    val resetRequestTimeout: Duration = 10.seconds,
)