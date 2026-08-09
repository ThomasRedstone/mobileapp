package coredevices.coreapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coredevices.ExperimentalDevicesFacade
import coredevices.NoOpExperimentalDevicesFacade
import coredevices.coreapp.di.apiModule
import coredevices.coreapp.di.desktopModule
import coredevices.coreapp.di.utilModule
import coredevices.coreapp.firebase.initializeFirebase
import coredevices.coreapp.ui.App
import coredevices.coreapp.util.initLogging
import coredevices.pebble.PebbleAppDelegate
import coredevices.pebble.watchModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // $TMPDIR (see coreapp-launch.sh, docs/ubuntu-touch-poc-plan.md Phase 6) isn't created by
    // anything else, and Room's bundled SQLite driver needs it to already exist.
    System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).mkdirs() }

    val koinApp = startKoin {
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
    installUncaughtExceptionLogging()
    initializeFirebase()
    // Android's MainApplication.onCreate() is the only other place this gets called - nothing
    // else drives it, so without this LibPebble/GATT server/Bluetooth state never initialize.
    koinApp.koin.get<PebbleAppDelegate>().init()

    application {
        // WindowPlacement.Maximized resizes the outer AWT frame under this Xwayland/Mir XWM
        // setup, but the inner Compose content canvas doesn't follow and stays at the 800x600
        // default - an explicit size (matched to the real screen) resizes the content itself.
        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val windowState = rememberWindowState(
            width = screenSize.width.dp,
            height = screenSize.height.dp,
        )
        // Must wrap Window: the factory is read when the window is created, and the default one
        // closes the window (and so the whole app) on any exception escaping composition.
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides LoggingWindowExceptionHandlerFactory
        ) {
            Window(onCloseRequest = ::exitApplication, title = "Core", state = windowState) {
                // Compose has no way to know this is a high-density phone display rather than a
                // normal desktop monitor, so dp-based UI renders at desktop scale - physically
                // tiny here. ~2.75x approximates this phone's real pixel density (Android's dp
                // is defined the same way: 1dp = 1/160in, so density = real dpi / 160).
                CompositionLocalProvider(LocalDensity provides Density(2.75f)) {
                    App()
                }
            }
        }
    }
}
