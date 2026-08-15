package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.exceptions.NotConnected
import org.freedesktop.dbus.interfaces.Properties

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val POLL_INTERVAL_MS = 3_000L

// closeGattServerWhenBtDisabled defaults to true on this platform, so a single transient poll
// failure reported as Disabled tears down the GATT server on a real connection — require two
// consecutive failures before treating the adapter as actually down.
private const val CONSECUTIVE_FAILURES_BEFORE_DISABLED = 2

private val logger = Logger.withTag("BluetoothState")

// Polls the adapter's Powered property over D-Bus (dbus-java) rather than subscribing to
// PropertiesChanged, matching the polling pattern already used for pairing state.
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? = flow {
    var connection = buildSystemBusConnection()
    var props = connection.getRemoteObject("org.bluez", ADAPTER_PATH, Properties::class.java)
    try {
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
                // A NotConnected connection never recovers on its own — rebuild it, or every
                // subsequent poll fails forever.
                if (e is NotConnected) {
                    runCatching { connection.disconnect() }
                    connection = buildSystemBusConnection()
                    props = connection.getRemoteObject("org.bluez", ADAPTER_PATH, Properties::class.java)
                }
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
