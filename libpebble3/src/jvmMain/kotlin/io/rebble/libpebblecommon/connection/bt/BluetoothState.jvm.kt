package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.interfaces.Properties

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val POLL_INTERVAL_MS = 3_000L

// A single failed poll used to be reported as Disabled outright - confirmed live (dmesg/
// bluetoothd showed no real adapter power event at the exact moment) this was a real,
// self-inflicted cause of dropped connections: closeGattServerWhenBtDisabled defaults to true on
// this platform, so a transient D-Bus hiccup on this poll - not a real Powered=false - was
// enough to make the app tear down its own GATT server. Two consecutive failures (~6s of
// confirmed-down, not one blip) before treating it as real.
private const val CONSECUTIVE_FAILURES_BEFORE_DISABLED = 2

private val logger = Logger.withTag("BluetoothState")

// Polls the adapter's Powered property over D-Bus (dbus-java) rather than subscribing to
// PropertiesChanged, matching the polling pattern already used for pairing state.
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? = flow {
    val connection = buildSystemBusConnection()
    try {
        val props = connection.getRemoteObject("org.bluez", ADAPTER_PATH, Properties::class.java)
        var last: BluetoothState? = null
        var consecutiveFailures = 0
        while (true) {
            val state = try {
                val powered = props.Get<Boolean>("org.bluez.Adapter1", "Powered") == true
                consecutiveFailures = 0
                if (powered) BluetoothState.Enabled else BluetoothState.Disabled
            } catch (e: Exception) {
                consecutiveFailures++
                logger.w(e) { "Couldn't poll adapter Powered state (failure $consecutiveFailures)" }
                if (consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_DISABLED) {
                    last
                } else {
                    BluetoothState.Disabled
                }
            }
            if (state != null && state != last) {
                emit(state)
                last = state
            }
            delay(POLL_INTERVAL_MS)
        }
    } finally {
        connection.disconnect()
    }
}
