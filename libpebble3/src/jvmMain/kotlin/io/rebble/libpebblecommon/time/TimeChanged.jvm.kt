package io.rebble.libpebblecommon.time

import io.rebble.libpebblecommon.connection.AppContext

// No portable JVM API for OS time/timezone-change notifications (see docs/ubuntu-touch-poc-plan.md).
object LinuxTimeChanged : TimeChanged {
    override fun registerForTimeChanges(onChanged: () -> Unit) {}
}

actual fun createTimeChanged(appContext: AppContext): TimeChanged = LinuxTimeChanged