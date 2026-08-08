package coredevices.util

// No permission has a full-screen-intent-style request flow on desktop JVM.
actual fun Permission.requestIsFullScreen(): Boolean = false
