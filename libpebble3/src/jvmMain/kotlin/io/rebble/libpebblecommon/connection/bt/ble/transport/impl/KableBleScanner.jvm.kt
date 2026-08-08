package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow

// Kable has no Linux backend, so this doesn't construct a KableBleScanner —
// LinuxBleScanner talks to BlueZ directly via busctl. createKableAdvertisementsFlow
// and Identifier.asPebbleBleIdentifier below exist only to satisfy the expect/actual
// contract; they're Kable-specific and unreachable from LinuxBleScanner.
actual fun kableBleScanner(bleConfigFlow: BleConfigFlow): BleScanner = LinuxBleScanner(bleConfigFlow)

internal actual fun createKableAdvertisementsFlow(bleConfig: BleConfig): Flow<Advertisement> = TODO("Not yet implemented")

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier {
    TODO("Not yet implemented")
}

actual fun configureKableCentral(stateRestoration: Boolean) {
    // No-op: CoreBluetooth state restoration is iOS-only.
}
