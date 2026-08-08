package coredevices.coreapp.ui.screens

// No foreground-service concept on desktop - bug report generation just runs in-process.
actual fun startForegroundService() {}

actual fun notifyState(message: String) {}

actual fun stopForegroundService() {}
