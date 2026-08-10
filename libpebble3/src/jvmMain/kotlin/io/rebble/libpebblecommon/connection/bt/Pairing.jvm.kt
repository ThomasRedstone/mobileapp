package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.Device1
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.interfaces.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * BlueZ over `dbus-java` for the BLE bond lifecycle. Real, proven flow on real hardware:
 * `Device1.Pair()` produces pairing prompts on both devices, and `Device1.Paired` flips true
 * once accepted (docs/ubuntu-touch-poc-plan.md).
 */
private val logger = Logger.withTag("Pairing")
private val POLL_INTERVAL = 1.seconds

// Reused across calls rather than opened per poll - SASL handshake overhead per connection is
// real, and DbusConnectedGattClient already establishes the same "one connection, many calls"
// pattern for the GATT side.
private val connection by lazy { buildSystemBusConnection() }

private fun devicePath(identifier: PebbleBleIdentifier): String =
    "/org/bluez/hci0/dev_" + identifier.asString.replace(":", "_")

private fun isPaired(devicePath: String): Boolean = try {
    val props = connection.getRemoteObject("org.bluez", devicePath, Properties::class.java)
    props.Get<Boolean>("org.bluez.Device1", "Paired") == true
} catch (e: Exception) {
    logger.e(e) { "couldn't read Paired for $devicePath" }
    false
}

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean = isPaired(devicePath(identifier))

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    logger.d("createBond()")
    return try {
        connection.getRemoteObject("org.bluez", devicePath(identifier), Device1::class.java).Pair()
        true
    } catch (e: Exception) {
        logger.e(e) { "Pair() failed" }
        false
    }
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivityWatcher: ConnectivityWatcher,
    connectionScope: ConnectionCoroutineScope,
): Flow<BluetoothDevicePairEvent> = flow {
    val path = devicePath(identifier)
    var lastPaired: Boolean? = null
    // Polls the Paired property rather than subscribing to PropertiesChanged - adequate for the
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
