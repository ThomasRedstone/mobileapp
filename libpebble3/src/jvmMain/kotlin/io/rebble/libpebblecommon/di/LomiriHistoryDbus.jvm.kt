package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.currentUnixUid
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * `dbus-java` plumbing for `com.lomiri.HistoryService` - Ubuntu Touch's SMS+call history service,
 * covered by the `history` AppArmor policy group. Verified live against the real service
 * (`busctl --user introspect com.lomiri.HistoryService /com/lomiri/HistoryService`) rather than
 * assumed from docs - see docs/ubuntu-touch-notification-bridge-plan.md.
 *
 * Session bus, not system bus (unlike BluezDbus.jvm.kt) - HistoryService, like the rest of
 * Lomiri's shell services, runs in the phablet user's own session.
 */
internal fun buildSessionBusConnection(): DBusConnection {
    // Same dbus-java EXTERNAL SASL auto-detection bug BluezDbus.jvm.kt works around for the
    // system bus (sends UID 0 in this sandboxed environment) - confirmed live: addSigHandler()
    // on an unpatched session-bus connection threw a NullPointerException from AddMatch()
    // ("this.dbus" never got initialized), the same failure shape.
    val builder = DBusConnectionBuilder.forSessionBus()
    currentUnixUid()?.let { uid ->
        builder.transportConfig().configureSasl().withSaslUid(uid).back()
    }
    return builder.build()
}

// Event "type" field values, confirmed empirically: QueryEvents(type=0, ...) returns only SMS/MMS
// events (field schema: message/messageType/messageStatus/senderId/threadId/...), QueryEvents(
// type=1, ...) returns only voice-call events (field schema: duration/missed/senderId/...).
internal const val HISTORY_EVENT_TYPE_TEXT = 0
internal const val HISTORY_EVENT_TYPE_CALL = 1

@DBusInterfaceName("com.lomiri.HistoryService")
internal interface HistoryService : DBusInterface {
    // Real signature confirmed via introspection: ia{sv}a{sv} (type, sort properties, filter
    // properties) -> s (an EventView object path). Empty sort/filter maps confirmed to work -
    // real object path returned, real data behind it.
    fun QueryEvents(
        type: Int,
        sort: Map<String, Variant<*>>,
        filter: Map<String, Variant<*>>,
    ): String

    class EventsAdded(path: String, val events: List<Map<String, Variant<*>>>) : DBusSignal(path, events)
}

@DBusInterfaceName("com.lomiri.HistoryService.EventView")
internal interface HistoryEventView : DBusInterface {
    fun NextPage(): List<Map<String, Variant<*>>>
    fun GetTotalCount(): Int
    fun Destroy()
}

/** Connects to an already-existing EventView object path (as opposed to `HistoryService` itself,
 *  which lives at a fixed path) - dbus-java resolves the interface's methods against whatever
 *  path is passed to `getRemoteObject`, so this needs its own helper rather than reusing a single
 *  proxy instance. */
internal fun DBusConnection.historyEventView(path: String): HistoryEventView =
    getRemoteObject("com.lomiri.HistoryService", path, HistoryEventView::class.java)

private fun Map<String, Variant<*>>.string(key: String): String? = this[key]?.value as? String
private fun Map<String, Variant<*>>.long(key: String): Long? = when (val v = this[key]?.value) {
    is Number -> v.toLong()
    else -> null
}
internal fun Map<String, Variant<*>>.int(key: String): Int? = when (val v = this[key]?.value) {
    is Number -> v.toInt()
    else -> null
}

internal data class HistoryCallEvent(
    val senderId: String,
    val callerName: String?,
    val timestampIso: String,
    val durationSeconds: Long,
    val missed: Boolean,
)

internal data class HistoryTextEvent(
    val senderId: String,
    val senderName: String?,
    val message: String,
    val timestampIso: String,
    val newEvent: Boolean,
)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Variant<*>>.participantAlias(): String? {
    val participants = this["participants"]?.value as? List<*> ?: return null
    val first = participants.firstOrNull() as? Map<String, Variant<*>> ?: return null
    return first.string("alias")
}

internal fun Map<String, Variant<*>>.toHistoryCallEvent(): HistoryCallEvent? {
    val senderId = string("senderId") ?: return null
    val timestamp = string("timestamp") ?: return null
    return HistoryCallEvent(
        senderId = senderId,
        callerName = participantAlias(),
        timestampIso = timestamp,
        durationSeconds = long("duration") ?: 0L,
        missed = (long("missed") ?: 0L) != 0L,
    )
}

internal fun Map<String, Variant<*>>.toHistoryTextEvent(): HistoryTextEvent? {
    val senderId = string("senderId") ?: return null
    val message = string("message") ?: return null
    val timestamp = string("timestamp") ?: return null
    return HistoryTextEvent(
        senderId = senderId,
        senderName = participantAlias(),
        message = message,
        timestampIso = timestamp,
        newEvent = this["newEvent"]?.value as? Boolean ?: false,
    )
}
