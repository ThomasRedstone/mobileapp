package coredevices.libindex

import coredevices.libindex.device.IndexDevice
import coredevices.libindex.device.IndexPlatformBluetoothAssociations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LibIndex : Scanning, Rings {
    fun init(bluetoothPermissionChanged: Flow<Boolean>)
}

typealias IndexDevices = StateFlow<List<IndexDevice>>

interface Scanning {
    val isScanning: StateFlow<Boolean>
    fun startScan()
    fun stopScan()
}

interface Rings {
    val rings: IndexDevices
    fun warnIfNoCompanionAssociations()
    fun init()
}

class RealLibIndex(
    private val scanning: Scanning,
    private val deviceRepo: Rings,
    private val associations: IndexPlatformBluetoothAssociations?
): LibIndex, Scanning by scanning, Rings by deviceRepo {
    override fun init(bluetoothPermissionChanged: Flow<Boolean>) {
        if (IndexPlatformBluetoothAssociations.isEnabled) {
            associations?.init(bluetoothPermissionChanged)
        }
        deviceRepo.init()
    }
}