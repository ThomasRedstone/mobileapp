package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.NewCalendarEvent
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.MissedCall
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.OtherPebbleApp
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * No-op stand-ins for phone-integration features (calendar, calls, contacts,
 * music, geolocation, notifications) that have no meaningful equivalent on a
 * Linux desktop JVM target — this platform's whole point is the BLE
 * connection to the watch, not replicating Android/iOS's OS integrations.
 * Each returns "no data"/"no permission" rather than throwing, so the shared
 * code that calls them degrades gracefully instead of crashing.
 */

class LinuxSystemCalendar : SystemCalendar {
    override suspend fun getCalendars(): List<CalendarEntity> = emptyList()
    override suspend fun getCalendarEvents(
        calendar: CalendarEntity,
        startDate: Instant,
        endDate: Instant,
    ): List<CalendarEvent> = emptyList()
    override suspend fun enableSyncForCalendar(calendar: CalendarEntity) {}
    override fun registerForCalendarChanges(): Flow<Unit>? = null
    override fun hasPermission(): Boolean = false
    override suspend fun createEvent(event: NewCalendarEvent): String? = null
    override fun supportsPinActions(): Boolean = false
}

class LinuxCalendarActionHandler : PlatformCalendarActionHandler {
    override suspend fun invoke(pin: TimelinePin, action: BaseAction): TimelineActionResult =
        TimelineActionResult(success = false, icon = TimelineIcon.ResultFailed, title = "Not supported")
}

class LinuxSystemCallLog : SystemCallLog {
    override suspend fun getMissedCalls(start: Instant): List<MissedCall> = emptyList()
    override fun registerForMissedCallChanges(): Flow<Unit> = emptyFlow()
    override fun hasPermission(): Boolean = false
}

class LinuxLegacyPhoneReceiver : LegacyPhoneReceiver {
    override fun init(currentCall: MutableStateFlow<Call?>) {}
}

class LinuxSystemMusicControl : SystemMusicControl {
    override fun play() {}
    override fun pause() {}
    override fun playPause() {}
    override fun nextTrack() {}
    override fun previousTrack() {}
    override fun volumeDown() {}
    override fun volumeUp() {}
    override val playbackState: StateFlow<PlaybackStatus?> = MutableStateFlow(null)
}

class LinuxSystemGeolocation : SystemGeolocation {
    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult = GeolocationPositionResult.Error("Geolocation not supported on this platform")
    override suspend fun watchPosition(interval: Duration, highAccuracy: Boolean): Flow<GeolocationPositionResult> =
        emptyFlow()
}

class LinuxSystemContacts : SystemContacts {
    override fun registerForContactsChanges(): Flow<Unit> = emptyFlow()
    override suspend fun getContacts(): List<SystemContact> = emptyList()
    override fun hasPermission(): Boolean = false
    override suspend fun getContactImage(lookupKey: String): ImageBitmap? = null
}

class LinuxOtherPebbleApps : OtherPebbleApps {
    override fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>> =
        MutableStateFlow(emptyList())
}

class LinuxNotificationAppsSync : NotificationAppsSync {
    // No OS notification-listener API to sync from on this platform.
    override fun init() {}
}

class LinuxNotificationListenerConnection : NotificationListenerConnection {
    override fun init(libPebble: LibPebble) {}
}

class LinuxNotificationActionHandler : PlatformNotificationActionHandler {
    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult =
        TimelineActionResult(success = false, icon = TimelineIcon.ResultFailed, title = "Not supported")
}
