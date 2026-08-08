package coredevices.ring.util

import co.touchlab.kermit.Logger
import kotlin.time.Instant

// No system calendar app to hand off to on desktop/Ubuntu Touch.
actual fun openSystemCalendarAt(startTime: Instant) {
    Logger.withTag("CalendarLinks").d { "No desktop calendar integration; ignoring open at $startTime" }
}
