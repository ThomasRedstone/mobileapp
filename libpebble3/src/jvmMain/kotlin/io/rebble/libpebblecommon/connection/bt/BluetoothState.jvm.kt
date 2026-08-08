package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.BusctlDbus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val POLL_INTERVAL_MS = 3_000L

// busctl can only call methods, not subscribe to PropertiesChanged signals (see
// docs/ubuntu-touch-poc-plan.md), so this polls the adapter's Powered property rather than
// reacting to it live.
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState> = flow {
    var last: BluetoothState? = null
    while (true) {
        val powered = BusctlDbus.getProperty("org.bluez", ADAPTER_PATH, "org.bluez.Adapter1", "Powered")
            ?.trim()?.endsWith("true") == true
        val state = if (powered) BluetoothState.Enabled else BluetoothState.Disabled
        if (state != last) {
            emit(state)
            last = state
        }
        delay(POLL_INTERVAL_MS)
    }
}
