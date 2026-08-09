package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.Peripheral
import com.juul.kable.toIdentifier
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier

/**
 * Unlike [LinuxBleScanner]/`GattServer.jvm.kt`, this doesn't need a
 * hand-rolled D-Bus path: Kable's JVM target has a real backend, `btleplug`
 * (bundled native libs including `linux-aarch64`, matching this platform),
 * so the commonMain [io.rebble.libpebblecommon.connection.bt.ble.transport.impl.KableGattConnector]/
 * `KableConnectedGattClient` — the same client code Android and iOS use —
 * works here unmodified. Only this factory function differs per platform.
 *
 * `identifier.asString` is a colon-separated MAC address everywhere else in
 * this jvmMain BLE stack (`LinuxBleScanner`, `GattServer.jvm.kt`,
 * `Pairing.jvm.kt`); btleplug's Linux (BlueZ) backend identifies peripherals
 * by address the same way, but this specific path — reconstructing an
 * `Identifier` from a bare address string rather than from a live scan
 * `Advertisement` — hasn't been exercised against real hardware yet.
 */
private val logger = Logger.withTag("KableGattClient")

actual fun peripheralFromIdentifier(
    identifier: PebbleBleIdentifier,
    name: String,
    autoConnect: Boolean,
): Peripheral? {
    // autoConnect has no JVM/btleplug equivalent (PeripheralBuilder has no
    // autoConnectIf like AndroidPeripheral does) -- connects normally either way.
    return try {
        // btleplug's Linux (BlueZ) backend identifies peripherals by D-Bus object path, not a
        // bare MAC address - a colon-separated address here fails PeripheralId's own parsing
        // ("expected value, line 1, column 1"), confirmed against real hardware.
        val dbusPath = "/org/bluez/hci0/dev_${identifier.asString.replace(":", "_")}"
        Peripheral(dbusPath.toIdentifier()) {}
    } catch (e: Exception) {
        logger.e(e) { "error constructing Peripheral for $identifier" }
        null
    }
}

actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int = mtu

actual suspend fun Peripheral.refreshServicesNative(): Boolean = false
