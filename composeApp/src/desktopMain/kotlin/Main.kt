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
import co.touchlab.kermit.Logger
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
import coredevices.util.AppDirs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.telemetry.DeviceTelemetry
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.RandomAccessFile

// Held for the process lifetime so the underlying FileLock isn't released early by GC.
private var singleInstanceLockChannel: java.nio.channels.FileChannel? = null

// Ubuntu Touch's SIGSTOP-on-background lifecycle (see docs/ubuntu-touch-architectural-paths.md)
// leaves old instances running indefinitely rather than exiting cleanly - a stale instance then
// races a freshly launched one for the same BLE connection, corrupting both (observed live:
// PPoG desync, "Unknown watch" in the UI). A lock file, not just "kill old processes before
// deploying", covers the case a user relaunches from the launcher without redeploying too.
private fun exitIfAnotherInstanceIsRunning() {
    val lockFile = AppDirs.cacheDir().apply { mkdirs() }.resolve("single-instance.lock")
    val channel = RandomAccessFile(lockFile, "rw").channel
    if (channel.tryLock() == null) {
        System.err.println("Another coreapp instance already holds $lockFile - exiting.")
        kotlin.system.exitProcess(1)
    }
    singleInstanceLockChannel = channel
}

// Backend bring-up, shared by both the normal UI launch and headless mode below - nothing here
// is Compose/AWT-specific; PebbleAppDelegate and everything under it is plain commonMain.
internal fun initCoreapp() {
    exitIfAnotherInstanceIsRunning()

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
    DeviceTelemetry.init(serviceName = "coreapp", serviceVersion = appVersionFromEnv())
    DeviceTelemetry.event(eventKind = "app.start", message = "coreapp started")
    // Android's MainApplication.onCreate() is the only other place this gets called - nothing
    // else drives it, so without this LibPebble/GATT server/Bluetooth state never initialize.
    koinApp.koin.get<PebbleAppDelegate>().init()
    // LibPebble3.create() (inside watchModule's single) builds its own internal Koin instance,
    // separate from koinApp above - ErrorTracker lives only in that inner graph, unreachable via
    // koinApp.koin.get() directly (confirmed live: NoDefinitionFoundException). LibPebble itself
    // is the one thing from that inner graph koinApp does expose (watchModule binds it), and it
    // implements Errors, so this reaches the same event stream without depending on koinApp
    // resolving LibPebble earlier than PebbleAppDelegate.init() already does today.
    forwardErrorsToTelemetry(koinApp.koin.get())
}

// lomiri-app-launch sets APP_ID to "<package>_<hook>_<version>" (e.g.
// "coreapp.thomasredstone_coreapp_0.1.62") for every click it starts - confirmed live via
// /proc/<pid>/environ - so the version this build actually is can be read at runtime instead of
// hand-maintaining a second copy of ubuntuTouchApp/manifest.json's version. Absent outside a
// click launch (a plain desktop dev run), hence the fallback.
private fun appVersionFromEnv(): String =
    System.getenv("APP_ID")?.substringAfterLast('_') ?: "unknown"

// See ~/own/ut/ut-telemetry-broker.md. GlobalScope matches this codebase's own existing pattern
// for an app-lifetime background collector (PebbleAppDelegate.init() uses the same thing) -
// LibPebbleCoroutineScope isn't an option here for the same inner-Koin-instance reason noted
// above.
private fun forwardErrorsToTelemetry(libPebble: LibPebble) {
    kotlinx.coroutines.GlobalScope.launch {
        libPebble.userFacingErrors.collect { error ->
            DeviceTelemetry.event(
                eventKind = "error.${error::class.simpleName}",
                message = error.message,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initCoreapp()

    // TEMPORARY validation spike for docs/ubuntu-touch-poc-plan.md Phase 1's core-service split
    // (see also docs/ubuntu-touch-architectural-paths.md) - same already-installed/AppArmor-
    // permitted binary and entry point, gated by an env var rather than a separate main class,
    // so it doesn't need its own exec grant. Proves the backend holds a connection with zero
    // Compose/AWT/Xwayland involvement before committing to a real systemd --user daemon + IPC
    // bridge. Not wired into coreapp-launch.sh - remove once diagnosed.
    if (System.getenv("COREAPP_HEADLESS") == "1") {
        Logger.withTag("Main").i { "COREAPP_HEADLESS=1: backend initialized, no UI - blocking forever" }
        java.util.concurrent.CountDownLatch(1).await()
        return
    }

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
