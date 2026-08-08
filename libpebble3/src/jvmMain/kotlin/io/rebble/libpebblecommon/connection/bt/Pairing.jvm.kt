package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.BusctlDbus
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

/**
 * BlueZ over `busctl` (see [BusctlDbus]) for the BLE bond lifecycle. Real,
 * proven flow on real hardware: `Device1.Pair()` produces pairing prompts
 * on both devices, and `Device1.Paired` flips true once accepted
 * (docs/ubuntu-touch-poc-plan.md).
 */
private val logger = Logger.withTag("Pairing")
private val POLL_INTERVAL = 1.seconds

private fun devicePath(identifier: PebbleBleIdentifier): String =
    "/org/bluez/hci0/dev_" + identifier.asString.replace(":", "_")

private fun isPaired(devicePath: String): Boolean {
    val output = BusctlDbus.getProperty("org.bluez", devicePath, "org.bluez.Device1", "Paired")
        ?: return false
    return output.trim().endsWith("true")
}

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean = isPaired(devicePath(identifier))

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    logger.d("createBond()")
    val result = BusctlDbus.call("org.bluez", devicePath(identifier), "org.bluez.Device1", "Pair")
    return result != null
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivityWatcher: ConnectivityWatcher,
    connectionScope: ConnectionCoroutineScope,
): Flow<BluetoothDevicePairEvent> = flow {
    val path = devicePath(identifier)
    var lastPaired: Boolean? = null
    // No D-Bus signal subscription from the JVM side (busctl is call-only,
    // see BusctlDbus) -- poll the Paired property instead. Adequate for the
    // bond handshake, which isn't latency-sensitive.
    while (true) {
        val paired = isPaired(path)
        if (paired != lastPaired) {
            lastPaired = paired
            emit(
                BluetoothDevicePairEvent(
                    device = identifier,
                    bondState = if (paired) BOND_BONDED else BOND_NONE,
                    unbondReason = null,
                )
            )
        }
        delay(POLL_INTERVAL)
    }
}

// BT Classic isn't supported on this platform (BlePlatformConfig.supportsBtClassic = false).
actual fun isBondedClassic(identifier: PebbleBtClassicIdentifier): Boolean = false

actual fun createBondClassic(identifier: PebbleBtClassicIdentifier): Boolean = false

actual fun getBluetoothClassicDevicePairEvents(
    context: AppContext,
    identifier: PebbleBtClassicIdentifier,
): Flow<BluetoothClassicDevicePairEvent> = flow { }
