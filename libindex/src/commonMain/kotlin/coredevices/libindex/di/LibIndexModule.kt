package coredevices.libindex.di

import coredevices.libindex.LibIndex
import coredevices.libindex.RealLibIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext

// Real device scanning/pairing bindings (RealScanning, IndexDeviceManager, RealIndexPairing,
// IndexDeviceFactory) live in platformLibIndexModule's android/iOS actuals, not here - they
// need coredevices.haversine, which has no jvm() variant (docs/ubuntu-touch-poc-plan.md).
expect val platformLibIndexModule: Module

//TODO: don't rely on app global Koin
val libIndexModule = module {
    includes(platformLibIndexModule)
    single { LibIndexCoroutineScope(Dispatchers.Default) }
    single {
        RealLibIndex(
            get(),
            get(),
            getOrNull()
        )
    } bind LibIndex::class
}

class LibIndexCoroutineScope(override val coroutineContext: CoroutineContext) : CoroutineScope
