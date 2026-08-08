package coredevices.libindex.di

import coredevices.libindex.Rings
import coredevices.libindex.Scanning
import coredevices.libindex.device.IndexDeviceFactory
import coredevices.libindex.device.IndexDeviceManager
import coredevices.libindex.device.IndexPairing
import coredevices.libindex.device.RealIndexPairing
import coredevices.libindex.device.RealScanning
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Real, haversine-backed device scanning/pairing bindings, shared by android's and iOS's
 *  platformLibIndexModule actuals. */
val mobileLibIndexModule: Module = module {
    singleOf(::RealIndexPairing) bind IndexPairing::class
    singleOf(::IndexDeviceFactory)
    single {
        IndexDeviceManager(
            get(),
            get(),
            get(),
            get(),
            getOrNull(),
            get(),
            get()
        )
    } bind Rings::class
    singleOf(::RealScanning) bind Scanning::class
}
