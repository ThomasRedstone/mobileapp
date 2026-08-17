package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
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
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * No-op stand-ins for phone-integration features (calendar, calls, contacts,
 * geolocation, notifications) that have no meaningful equivalent on a Linux
 * desktop JVM target — this platform's whole point is the BLE connection to
 * the watch, not replicating Android/iOS's OS integrations. Each returns "no
 * data"/"no permission" rather than throwing, so the shared code that calls
 * them degrades gracefully instead of crashing. Music control is the one
 * exception - see [LinuxSystemMusicControl] below, backed by MPRIS.
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

/**
 * Backed by MPRIS (`org.mpris.MediaPlayer2.*`) over the session bus - see MprisDbus.jvm.kt.
 * Unlike `HistoryService`/`NotificationBridge1`, an MPRIS player's bus name isn't fixed (it's
 * `org.mpris.MediaPlayer2.<app>`, possibly with a `.instanceN` suffix for multiple players), so
 * it's discovered via `org.freedesktop.DBus.ListNames`/`NameOwnerChanged` rather than assumed.
 * Prefers Sonic Player (this fleet's only real MPRIS server today) when present, falls back to
 * any other player found.
 */
class LinuxSystemMusicControl(
    private val sessionBus: DBusConnection,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : SystemMusicControl {
    private val logger = Logger.withTag("LinuxSystemMusicControl")

    private val activePlayerBusName: Flow<String?> = callbackFlow {
        val busDriver = sessionBus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        var current: String? = null

        fun refresh() {
            val names = runCatching {
                busDriver.ListNames().filter { it.startsWith(MPRIS_BUS_PREFIX) }
            }.getOrDefault(emptyList())
            val chosen = names.firstOrNull { it == PREFERRED_BUS_NAME } ?: names.firstOrNull()
            if (chosen != current) {
                current = chosen
                trySend(chosen)
            }
        }
        refresh()

        val handler = DBusSigHandler<DBus.NameOwnerChanged> { signal ->
            if (signal.name.startsWith(MPRIS_BUS_PREFIX)) refresh()
        }
        runCatching { sessionBus.addSigHandler(DBus.NameOwnerChanged::class.java, handler) }
            .onFailure { logger.w(it) { "couldn't subscribe to NameOwnerChanged - MPRIS players appearing/disappearing won't be picked up live" } }
        awaitClose {
            runCatching { sessionBus.removeSigHandler(DBus.NameOwnerChanged::class.java, handler) }
        }
    }

    override val playbackState: StateFlow<PlaybackStatus?> = activePlayerBusName
        .flatMapLatest { busName -> busName?.let { playerStateFlow(it) } ?: flowOf(null) }
        .stateIn(libPebbleCoroutineScope, SharingStarted.Eagerly, null)

    /** Live state for one specific MPRIS player, re-read on every `PropertiesChanged` signal from
     *  its `Player` interface. Re-subscribed by [activePlayerBusName] whenever the tracked player
     *  changes - `flatMapLatest` cancels the previous player's subscription automatically. */
    private fun playerStateFlow(busName: String): Flow<PlaybackStatus?> = callbackFlow {
        trySend(readState(busName))
        val playerProxy = sessionBus.getRemoteObject(busName, MPRIS_OBJECT_PATH, MprisPlayer::class.java)
        val handler = DBusSigHandler<Properties.PropertiesChanged> { signal ->
            if (signal.interfaceName == MPRIS_PLAYER_INTERFACE) trySend(readState(busName))
        }
        runCatching { sessionBus.addSigHandler(Properties.PropertiesChanged::class.java, playerProxy, handler) }
            .onFailure { logger.w(it) { "couldn't subscribe to $busName PropertiesChanged" } }
        awaitClose {
            runCatching { sessionBus.removeSigHandler(Properties.PropertiesChanged::class.java, playerProxy, handler) }
        }
    }

    private fun readState(busName: String): PlaybackStatus? = try {
        val props = sessionBus.getRemoteObject(busName, MPRIS_OBJECT_PATH, Properties::class.java)
        val playerProps = props.GetAll(MPRIS_PLAYER_INTERFACE)
        val identity = runCatching { props.Get<String>(MPRIS_ROOT_INTERFACE, "Identity") }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val metadata = playerProps["Metadata"]?.value as? Map<String, Variant<*>> ?: emptyMap()
        val volumeFraction = (playerProps["Volume"]?.value as? Number)?.toDouble() ?: 1.0
        PlaybackStatus(
            playerInfo = PlayerInfo(
                packageId = busName,
                name = identity ?: busName.removePrefix(MPRIS_BUS_PREFIX),
            ),
            playbackState = when (playerProps.mprisString("PlaybackStatus")) {
                "Playing" -> PlaybackState.Playing
                else -> PlaybackState.Paused
            },
            currentTrack = metadata.takeIf { it.isNotEmpty() }?.let {
                MusicTrack(
                    title = it.mprisString("xesam:title"),
                    artist = it.mprisArtist(),
                    album = it.mprisString("xesam:album"),
                    length = (it.mprisLong("mpris:length") ?: 0L).microseconds,
                    trackNumber = it.mprisInt("xesam:trackNumber"),
                )
            },
            // MPRIS doesn't require Position to notify via PropertiesChanged (players can update
            // it many times a second) - this is a snapshot at read time, not live-ticking.
            playbackPositionMs = (playerProps.mprisLong("Position") ?: 0L) / 1000,
            playbackRate = (playerProps["Rate"]?.value as? Number)?.toFloat() ?: 1f,
            shuffle = playerProps["Shuffle"]?.value as? Boolean ?: false,
            repeat = when (playerProps.mprisString("LoopStatus")) {
                "Track" -> RepeatType.One
                "Playlist" -> RepeatType.All
                else -> RepeatType.Off
            },
            volume = (volumeFraction * 100).roundToInt().coerceIn(0, 100),
        )
    } catch (e: Exception) {
        logger.w(e) { "Couldn't read MPRIS state from $busName" }
        null
    }

    private fun withPlayer(block: (MprisPlayer) -> Unit) {
        val busName = playbackState.value?.playerInfo?.packageId ?: run {
            logger.d { "No MPRIS player available" }
            return
        }
        libPebbleCoroutineScope.launch {
            try {
                block(sessionBus.getRemoteObject(busName, MPRIS_OBJECT_PATH, MprisPlayer::class.java))
            } catch (e: Exception) {
                logger.w(e) { "MPRIS call to $busName failed" }
            }
        }
    }

    override fun play() = withPlayer { it.Play() }
    override fun pause() = withPlayer { it.Pause() }
    override fun playPause() = withPlayer { it.PlayPause() }
    override fun nextTrack() = withPlayer { it.Next() }
    override fun previousTrack() = withPlayer { it.Previous() }
    override fun volumeUp() = adjustVolume(VOLUME_STEP)
    override fun volumeDown() = adjustVolume(-VOLUME_STEP)

    private fun adjustVolume(delta: Double) {
        val busName = playbackState.value?.playerInfo?.packageId ?: return
        libPebbleCoroutineScope.launch {
            try {
                val props = sessionBus.getRemoteObject(busName, MPRIS_OBJECT_PATH, Properties::class.java)
                val current = (props.Get<Any>(MPRIS_PLAYER_INTERFACE, "Volume") as? Number)?.toDouble() ?: 1.0
                props.Set(MPRIS_PLAYER_INTERFACE, "Volume", (current + delta).coerceIn(0.0, 1.0))
            } catch (e: Exception) {
                logger.w(e) { "Couldn't change volume on $busName" }
            }
        }
    }

    private companion object {
        const val MPRIS_BUS_PREFIX = "org.mpris.MediaPlayer2."
        const val PREFERRED_BUS_NAME = "org.mpris.MediaPlayer2.sonicplayer"
        const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
        const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
        const val VOLUME_STEP = 0.1
    }
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
 * Two independent notification sources feed into this, not one:
 *
 * - SMS via `com.lomiri.HistoryService` (the `history` policy group) - a sanctioned, narrowly
 *   scoped OS API that exists specifically for this, subscribed to directly.
 * - Everything else via `org.thomasredstone.NotificationBridge1` (`../ut-notify`) - a standalone
 *   daemon that eavesdrops `org.freedesktop.Notifications.Notify` system-wide (something a
 *   confined Click structurally cannot do itself - see
 *   docs/ubuntu-touch-notification-bridge-plan.md) and re-broadcasts approved subscribers a
 *   kernel-verified source app identity alongside each notification's original arguments,
 *   unchanged. Subscription needs a one-time manual `bridge-ctl allow <identity>` from whoever
 *   administers the phone (see ../ut-notify/docs/notification-bridge-api.md) - NotAuthorized
 *   until then is expected, not a bug, and this degrades to "no generic notifications" rather
 *   than failing loudly.
 */
class LinuxNotificationListenerConnection(
    private val sessionBus: DBusConnection,
    private val libPebbleScope: LibPebbleCoroutineScope,
) : NotificationListenerConnection {
    override fun init(libPebble: LibPebble) {
        val smsHandler = DBusSigHandler<HistoryService.EventsAdded> { signal ->
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
        sessionBus.addSigHandler(HistoryService.EventsAdded::class.java, smsHandler)

        val bridgeHandler = DBusSigHandler<NotificationBridge1.NotificationReceived> { signal ->
            val notification = buildTimelineNotification(
                parentId = ANDROID_NOTIFICATIONS_UUID,
                timestamp = Clock.System.now(),
            ) {
                attributes {
                    title { signal.summary.ifBlank { signal.appName } }
                    body { signal.body }
                    tinyIcon { TimelineIcon.NotificationGeneric }
                    sender { signal.appName }
                }
            }
            libPebbleScope.launch { libPebble.sendNotification(notification) }
        }
        sessionBus.addSigHandler(NotificationBridge1.NotificationReceived::class.java, bridgeHandler)

        // Subscribe() is a blocking D-Bus round-trip - off init()'s own (synchronous) call
        // path, not stalling LibPebble.init()'s caller on a slow/unreachable daemon. Retries
        // with backoff rather than once: NotAuthorized is commonly transient (the admin runs
        // `bridge-ctl allow <identity>` after this app's first run, with no way for the daemon
        // to notify us when that happens), and a one-shot attempt would otherwise need a full
        // app restart to pick that up.
        libPebbleScope.launch {
            var retryDelay = SUBSCRIBE_RETRY_INITIAL_DELAY
            while (true) {
                try {
                    sessionBus.getRemoteObject(
                        NOTIFICATION_BRIDGE_BUS_NAME,
                        NOTIFICATION_BRIDGE_OBJECT_PATH,
                        NotificationBridge1::class.java,
                    ).Subscribe()
                    linuxTelephonyLogger.i { "Subscribed to NotificationBridge1" }
                    return@launch
                } catch (e: Exception) {
                    linuxTelephonyLogger.w(e) {
                        "Couldn't subscribe to NotificationBridge1 - not authorized yet " +
                            "(needs `bridge-ctl allow <identity>`) or the daemon isn't running; " +
                            "retrying in $retryDelay"
                    }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(SUBSCRIBE_RETRY_MAX_DELAY)
                }
            }
        }
    }

    private companion object {
        val SUBSCRIBE_RETRY_INITIAL_DELAY = 10.seconds
        val SUBSCRIBE_RETRY_MAX_DELAY = 5.minutes
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
