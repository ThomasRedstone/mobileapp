package coredevices.coreapp.di

import coredevices.libindex.IndexDevices
import coredevices.libindex.LibIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// The ring ('Index 01') is android/iOS-only (see docs/ubuntu-touch-poc-plan.md) - desktop has no
// :libindex binding of its own, but WatchHomeViewModel takes a LibIndex directly rather than
// through ExperimentalDevicesFacade, so a real binding is still needed to satisfy Koin.
object NoOpLibIndex : LibIndex {
    override val isScanning = MutableStateFlow(false)
    override val rings: IndexDevices = MutableStateFlow(emptyList())

    override fun startScan() {}
    override fun stopScan() {}
    override fun warnIfNoCompanionAssociations() {}
    override fun init() {}
    override fun init(bluetoothPermissionChanged: Flow<Boolean>) {}
}
