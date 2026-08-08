package coredevices.coreapp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
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
        // WindowPlacement.Maximized resizes the outer AWT frame under this Xwayland/Mir XWM
        // setup, but the inner Compose content canvas doesn't follow and stays at the 800x600
        // default - an explicit size (matched to the real screen) resizes the content itself.
        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val windowState = rememberWindowState(
            width = screenSize.width.dp,
            height = screenSize.height.dp,
        )
        Window(onCloseRequest = ::exitApplication, title = "Core", state = windowState) {
            App()
        }
    }
}
