package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthManagerFactory
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Real, health-kmp-backed bindings, shared by android's and iOS's platformWatchModule actuals. */
val mobileHealthModule: Module = module {
    single { HealthManagerFactory().createManager() }
    singleOf(::RealPlatformHealthSync) bind PlatformHealthSync::class
}
