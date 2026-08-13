package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.SMS_APP_UUID
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.NewCalendarEvent
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.BlockedReason
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
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusSigHandler
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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

private val linuxTelephonyLogger = Logger.withTag("LinuxTelephony")

// HistoryService timestamps ("2026-08-12T20:00:43.009") carry no timezone/offset - confirmed
// empirically (busctl introspection against a real device) - so they're parsed as wall-clock time
// in the device's own zone, matching how the rest of Lomiri's shell displays them.
private fun parseHistoryTimestamp(iso: String): Instant? = try {
    java.time.LocalDateTime.parse(iso)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .let { Instant.fromEpochMilliseconds(it.toEpochMilli()) }
} catch (e: DateTimeParseException) {
    linuxTelephonyLogger.w(e) { "Couldn't parse HistoryService timestamp: $iso" }
    null
}

/**
 * Backed by `com.lomiri.HistoryService` (the `history` AppArmor policy group), verified live
 * against the real service - see docs/ubuntu-touch-notification-bridge-plan.md and
 * LomiriHistoryDbus.jvm.kt. `getMissedCalls`/`registerForMissedCallChanges` feed the existing,
 * unchanged `MissedCallSyncer`, same as the Android implementation.
 *
 * Shares [sessionBus] with [LinuxNotificationListenerConnection] rather than each opening its own
 * connection - confirmed live this isn't just tidiness: two independently-built session-bus
 * connections racing each other during startup hit a real dbus-java bug (NullPointerException
 * from AddMatch(), "this.dbus" never initialized on one of them) that a single shared,
 * eagerly-built connection doesn't.
 */
class LinuxSystemCallLog(
    private val sessionBus: DBusConnection,
) : SystemCallLog {
    override suspend fun getMissedCalls(start: Instant): List<MissedCall> = try {
        val history = sessionBus.getRemoteObject(
            "com.lomiri.HistoryService",
            "/com/lomiri/HistoryService",
            HistoryService::class.java,
        )
        val viewPath = history.QueryEvents(HISTORY_EVENT_TYPE_CALL, emptyMap(), emptyMap())
        val view = sessionBus.historyEventView(viewPath)
        try {
            view.NextPage()
                .mapNotNull { it.toHistoryCallEvent() }
                .filter { it.missed }
                .mapNotNull { event ->
                    val timestamp = parseHistoryTimestamp(event.timestampIso) ?: return@mapNotNull null
                    if (timestamp < start) return@mapNotNull null
                    MissedCall(
                        callerNumber = event.senderId,
                        callerName = event.callerName,
                        blockedReason = BlockedReason.NotBlocked,
                        timestamp = timestamp,
                        duration = event.durationSeconds.seconds,
                    )
                }
        } finally {
            view.Destroy()
        }
    } catch (e: Exception) {
        linuxTelephonyLogger.w(e) { "Couldn't fetch missed calls from HistoryService" }
        emptyList()
    }

    override fun registerForMissedCallChanges(): Flow<Unit> = callbackFlow {
        val handler = DBusSigHandler<HistoryService.EventsAdded> { signal ->
            val hasCall = signal.events.any { it.int("type") == HISTORY_EVENT_TYPE_CALL }
            if (hasCall) trySend(Unit)
        }
        sessionBus.addSigHandler(HistoryService.EventsAdded::class.java, handler)
        awaitClose {
            sessionBus.removeSigHandler(HistoryService.EventsAdded::class.java, handler)
        }
    }

    override fun hasPermission(): Boolean = true
}

// Live call state (ringing/answer/hangup) needs com.lomiri.TelephonyServiceHandler's
// CallPropertiesChanged signal plus, for answering an incoming call specifically, the Telepathy
// Approver/Handler channel-dispatch protocol (com.lomiri.TelephonyServiceApprover) - genuinely
// unverified without a live call to test against, and getting the Approver dance wrong risks
// interfering with the phone's own real call handling. Deliberately left as a no-op rather than
// shipping a watch "answer" control that silently does nothing - see
// docs/ubuntu-touch-notification-bridge-plan.md.
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

/**
 * There's no OS notification-listener API on this platform to generically forward - see
 * docs/ubuntu-touch-notification-bridge-plan.md for why (org.freedesktop.Notifications is owned
 * by lomiri itself, not interceptable from a confined click). SMS specifically is real and
 * sanctioned, though, via `com.lomiri.HistoryService` (the `history` policy group) - subscribed
 * to directly here rather than through any generic listener pipeline, and synthesized into a
 * TimelineNotification the same way any other notification source would be.
 */
class LinuxNotificationListenerConnection(
    private val sessionBus: DBusConnection,
    private val libPebbleScope: LibPebbleCoroutineScope,
) : NotificationListenerConnection {
    override fun init(libPebble: LibPebble) {
        val handler = DBusSigHandler<HistoryService.EventsAdded> { signal ->
            signal.events
                .filter { it.int("type") == HISTORY_EVENT_TYPE_TEXT }
                .mapNotNull { it.toHistoryTextEvent() }
                .filter { it.newEvent }
                .forEach { event ->
                    val timestamp = parseHistoryTimestamp(event.timestampIso) ?: return@forEach
                    val notification = buildTimelineNotification(
                        parentId = SMS_APP_UUID,
                        timestamp = timestamp,
                    ) {
                        attributes {
                            title { event.senderName ?: event.senderId }
                            body { event.message }
                            tinyIcon { TimelineIcon.GenericSms }
                            sender { event.senderName ?: event.senderId }
                        }
                    }
                    libPebbleScope.launch { libPebble.sendNotification(notification) }
                }
        }
        sessionBus.addSigHandler(HistoryService.EventsAdded::class.java, handler)
    }
}

class LinuxNotificationActionHandler : PlatformNotificationActionHandler {
    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult =
        TimelineActionResult(success = false, icon = TimelineIcon.ResultFailed, title = "Not supported")
}
