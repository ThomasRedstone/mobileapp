package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.interfaces.Properties

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val POLL_INTERVAL_MS = 3_000L

// Polls the adapter's Powered property over D-Bus (dbus-java) rather than subscribing to
// PropertiesChanged, matching the polling pattern already used for pairing state.
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? = flow {
    val connection = buildSystemBusConnection()
    try {
        val props = connection.getRemoteObject("org.bluez", ADAPTER_PATH, Properties::class.java)
        var last: BluetoothState? = null
        while (true) {
            val powered = try {
                props.Get<Boolean>("org.bluez.Adapter1", "Powered") == true
            } catch (e: Exception) {
                false
            }
            val state = if (powered) BluetoothState.Enabled else BluetoothState.Disabled
            if (state != last) {
                emit(state)
                last = state
            }
            delay(POLL_INTERVAL_MS)
        }
    } finally {
        connection.disconnect()
    }
}
