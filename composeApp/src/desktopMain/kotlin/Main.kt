package coredevices.coreapp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
        // No explicit size defaults to a small fixed window (800x600) regardless of the real
        // display - maximized fills the actual Xwayland surface on the phone screen instead.
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(onCloseRequest = ::exitApplication, title = "Core", state = windowState) {
            App()
        }
    }
}
