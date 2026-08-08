package coredevices.pebble

import coredevices.pebble.health.NoOpPlatformHealthSync
import coredevices.pebble.health.PlatformHealthSync
import io.rebble.libpebblecommon.connection.AppContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

// Platform has no desktop case yet (see docs/ubuntu-touch-poc-plan.md); Android is the closer
// fit of the two existing values for feature-flag purposes until a real Jvm/Linux case is added.
actual val platformWatchModule: Module = module {
    single { AppContext() }
    single<Platform> { Platform.Android }
    singleOf(::PebbleJvmDelegate)
    single<PlatformHealthSync> { NoOpPlatformHealthSync }
}
