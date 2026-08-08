package coredevices.libindex.device

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// No BlueZ/D-Bus bonded-device query wired up yet, see docs/ubuntu-touch-poc-plan.md.
actual class IndexPlatformBluetoothAssociations {
    actual val associations: StateFlow<List<IndexAssociation>?> get() = throw NotImplementedError()
    actual val bondStateChanges: Flow<IndexBondStateUpdate> get() = throw NotImplementedError()

    actual fun init(bluetoothPermissionChanged: Flow<Boolean>) {
        throw NotImplementedError()
    }

    // No CompanionDeviceManager equivalent on the JVM; never warn.
    actual fun warnIfNoCompanionAssociations(): Unit = Unit

    actual companion object {
        actual val isEnabled: Boolean = false
    }
}
