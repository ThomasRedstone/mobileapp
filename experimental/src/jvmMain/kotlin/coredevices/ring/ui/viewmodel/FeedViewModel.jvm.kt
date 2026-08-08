package coredevices.ring.ui.viewmodel

import androidx.compose.ui.platform.ClipEntry
import co.touchlab.kermit.Logger
import java.awt.datatransfer.StringSelection
import kotlin.time.Instant

actual suspend fun makeTextClipEntry(text: String): ClipEntry {
    return ClipEntry(StringSelection(text))
}

// No desktop calendar integration.
actual suspend fun addCalendarEvent(
    title: String,
    startTime: Instant,
    endTime: Instant,
    allDay: Boolean
) {
    Logger.withTag("FeedViewModel").d { "No desktop calendar integration; dropping event '$title' at $startTime" }
}
