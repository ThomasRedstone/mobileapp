package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Which notifications from `org.thomasredstone.NotificationBridge1` reach the watch. The bridge
 * forwards every `org.freedesktop.Notifications.Notify` unchanged, by contract, so this is where
 * the phone's notifications get filtered - Android's NotificationHandler has its own version of
 * the same job.
 */
internal enum class BridgedNotificationDecision {
    SendToWatch,

    /** Lomiri's in-place volume/brightness bubble ([ON_SCREEN_FEEDBACK_HINTS]). */
    NotSentOnScreenFeedback,

    /** `transient`: the sender asked for it not to be kept, e.g. "connected to Wi-Fi". */
    NotSentTransient,

    /** `urgency` 0 (low): "joined the chat"-class. Pebble has no silent delivery, so on the
     *  watch the only choice is buzz or nothing. */
    NotSentLowUrgency,

    /** An update (`replaces_id` != 0) to a notification the watch already has, with the same
     *  title and body as what was last sent for that app. */
    NotSentDuplicateUpdate,

    /** An update to a notification the watch already has, inside [UPDATE_COOLDOWN] of the last
     *  notification sent for that app: a progress bar or a counter ticking over. */
    NotSentUpdateTooSoon,
}

/** How long after a notification from one app its in-place updates stay off the watch. */
internal val UPDATE_COOLDOWN: Duration = 60.seconds

private const val URGENCY_LOW = 0

/**
 * Remembers, per source app, what was last sent to the watch and when, so in-place updates
 * (`replaces_id` != 0) don't each buzz the watch as a new notification.
 *
 * The bridge only sees Notify calls, never the ids the notification server hands back, so an
 * update can't be tied to the exact notification it replaces. It is matched against the last
 * notification sent for the same source app instead. The trade-off: a second chat message that
 * an app posts as an update, inside [UPDATE_COOLDOWN] of the first, doesn't buzz the watch.
 */
internal class BridgedNotificationPolicy(private val now: () -> Instant) {
    private data class Sent(val summary: String, val body: String, val at: Instant)

    private val lastSent = mutableMapOf<String, Sent>()

    @Synchronized
    fun decide(
        sourceAppId: String,
        replacesId: Long,
        summary: String,
        body: String,
        hints: Map<String, Any?>,
    ): BridgedNotificationDecision {
        val decision = when {
            hints.keys.any { it in ON_SCREEN_FEEDBACK_HINTS } ->
                BridgedNotificationDecision.NotSentOnScreenFeedback
            hints["transient"].isTrue() -> BridgedNotificationDecision.NotSentTransient
            hints["urgency"].asInt() == URGENCY_LOW -> BridgedNotificationDecision.NotSentLowUrgency
            replacesId != 0L -> decideUpdate(sourceAppId, summary, body)
            else -> BridgedNotificationDecision.SendToWatch
        }
        if (decision == BridgedNotificationDecision.SendToWatch) {
            lastSent[sourceAppId] = Sent(summary, body, now())
        }
        return decision
    }

    private fun decideUpdate(sourceAppId: String, summary: String, body: String): BridgedNotificationDecision {
        val previous = lastSent[sourceAppId] ?: return BridgedNotificationDecision.SendToWatch
        return when {
            previous.summary == summary && previous.body == body ->
                BridgedNotificationDecision.NotSentDuplicateUpdate
            now() - previous.at < UPDATE_COOLDOWN -> BridgedNotificationDecision.NotSentUpdateTooSoon
            else -> BridgedNotificationDecision.SendToWatch
        }
    }
}

/** D-Bus hint values arrive wrapped; the policy works on the plain values. */
internal fun Map<String, Variant<*>>.unwrapHints(): Map<String, Any?> = mapValues { it.value.value }

// The spec types `transient` as a boolean and `urgency` as a byte, but senders differ (libnotify
// bindings send ints), so accept any number.
private fun Any?.isTrue(): Boolean = when (this) {
    is Boolean -> this
    is Number -> toLong() != 0L
    else -> false
}

private fun Any?.asInt(): Int? = when (this) {
    is Number -> toInt()
    else -> null
}
