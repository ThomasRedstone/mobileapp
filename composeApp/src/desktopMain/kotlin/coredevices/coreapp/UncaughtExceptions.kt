package coredevices.coreapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import co.touchlab.kermit.Logger
import java.awt.Window

private val logger = Logger.withTag("UncaughtExceptions")

/**
 * Compose Desktop's `DefaultWindowExceptionHandlerFactory` shows a Swing error dialog and then
 * dispatches `WINDOW_CLOSING` at the window. Since `application {}` exits once its last window
 * closes, any exception escaping composition took down the whole process - including the BLE
 * connection to the watch, which has nothing to do with whichever screen failed.
 *
 * Logging instead keeps the process alive. The failed composition is not recovered: Compose has
 * no way to catch an exception inside a composable, so this is the only seam available, and the
 * window may be left showing stale content until something recomposes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
object LoggingWindowExceptionHandlerFactory : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: Window) = WindowExceptionHandler { throwable ->
        logger.e(throwable) { "Uncaught exception in window composition or event handling" }
    }
}

/**
 * Covers threads AWT's event queue doesn't own - background coroutines, BLE callbacks, the
 * `GlobalScope` launches the sign-in buttons use.
 *
 * Unlike Android's equivalent in `MainApplication`, this doesn't delegate to the previously
 * installed handler: there is nothing behind it here (Crashlytics has no jvm artifact), and the
 * JVM's own fallback would only reprint what Kermit has already recorded.
 */
fun installUncaughtExceptionLogging() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        logger.e(throwable) { "Unhandled exception in thread ${thread.name}" }
    }
}
