package coredevices.coreapp.util

import coredevices.util.AppDirs

// Firebase Crashlytics has no jvm artifact (see the expect declarations in commonMain's
// logging.kt) -- no-op rather than crash or silently drop the app's whole crash-reporting path.
actual fun crashlyticsLog(message: String) {}

actual fun crashlyticsRecordException(throwable: Throwable) {}

actual fun getLogsCacheDir(): String = AppDirs.cacheDir("logs").path + "/"

actual fun generateDeviceSummaryPlatformDetails(): String = buildString {
    appendLine("Device Summary (Linux desktop)")
    appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    appendLine("Arch: ${System.getProperty("os.arch")}")
    appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    appendLine("Available processors: ${Runtime.getRuntime().availableProcessors()}")
    appendLine("Max memory: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
}
