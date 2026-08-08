package coredevices.coreapp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coredevices.ExperimentalDevicesFacade
import coredevices.NoOpExperimentalDevicesFacade
import coredevices.coreapp.di.apiModule
import coredevices.coreapp.di.desktopModule
import coredevices.coreapp.di.utilModule
import coredevices.coreapp.ui.App
import coredevices.coreapp.util.initLogging
import coredevices.pebble.watchModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun main() {
    startKoin {
        modules(
            desktopModule,
            apiModule,
            utilModule,
            watchModule,
            module {
                single<ExperimentalDevicesFacade> { NoOpExperimentalDevicesFacade }
            },
        )
    }
    initLogging()

    application {
        Window(onCloseRequest = ::exitApplication, title = "Core") {
            App()
        }
    }
}
