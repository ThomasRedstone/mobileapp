package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.di.BridgedNotificationDecision.NotSentDuplicateUpdate
import io.rebble.libpebblecommon.di.BridgedNotificationDecision.NotSentLowUrgency
import io.rebble.libpebblecommon.di.BridgedNotificationDecision.NotSentOnScreenFeedback
import io.rebble.libpebblecommon.di.BridgedNotificationDecision.NotSentTransient
import io.rebble.libpebblecommon.di.BridgedNotificationDecision.NotSentUpdateTooSoon
import io.rebble.libpebblecommon.di.BridgedNotificationDecision.SendToWatch
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BridgedNotificationPolicyTest {
    private var clock = Instant.fromEpochSeconds(1_800_000_000)
    private val policy = BridgedNotificationPolicy(now = { clock })

    private fun advance(by: Duration) {
        clock += by
    }

    private fun decide(
        replacesId: Long = 0,
        summary: String = "Alice",
        body: String = "hi",
        hints: Map<String, Any?> = emptyMap(),
        app: String = "chat.app_chat_1.0",
    ) = policy.decide(app, replacesId, summary, body, hints)

    @Test
    fun `an ordinary notification goes to the watch`() {
        assertEquals(SendToWatch, decide())
    }

    @Test
    fun `the volume bubble does not, under either hint name, whatever the value`() {
        // What ayatana-indicator-sound sends on every volume step.
        assertEquals(
            NotSentOnScreenFeedback,
            decide(hints = mapOf("x-lomiri-private-synchronous" to "true", "x-lomiri-non-shaped-icon" to "true")),
        )
        assertEquals(NotSentOnScreenFeedback, decide(hints = mapOf("x-canonical-private-synchronous" to "")))
    }

    @Test
    fun `transient notifications do not, as a boolean or a number`() {
        assertEquals(NotSentTransient, decide(hints = mapOf("transient" to true)))
        assertEquals(NotSentTransient, decide(hints = mapOf("transient" to 1)))
        assertEquals(SendToWatch, decide(hints = mapOf("transient" to false)))
    }

    @Test
    fun `low urgency does not, normal and critical do`() {
        assertEquals(NotSentLowUrgency, decide(hints = mapOf("urgency" to 0.toByte())))
        assertEquals(SendToWatch, decide(hints = mapOf("urgency" to 1.toByte()), app = "a_a_1"))
        assertEquals(SendToWatch, decide(hints = mapOf("urgency" to 2), app = "b_b_1"))
    }

    @Test
    fun `a progress bar buzzes once, not every tick`() {
        assertEquals(SendToWatch, decide(summary = "Downloading", body = "1%"))
        advance(2.seconds)
        assertEquals(NotSentUpdateTooSoon, decide(replacesId = 7, summary = "Downloading", body = "2%"))
        advance(2.seconds)
        assertEquals(NotSentUpdateTooSoon, decide(replacesId = 7, summary = "Downloading", body = "3%"))
    }

    @Test
    fun `an update that changes nothing never goes, however late`() {
        assertEquals(SendToWatch, decide())
        advance(10.seconds)
        assertEquals(NotSentDuplicateUpdate, decide(replacesId = 7))
        advance(UPDATE_COOLDOWN * 2)
        assertEquals(NotSentDuplicateUpdate, decide(replacesId = 7))
    }

    @Test
    fun `a changed update goes once the cooldown has passed`() {
        assertEquals(SendToWatch, decide(body = "hi"))
        advance(UPDATE_COOLDOWN)
        assertEquals(SendToWatch, decide(replacesId = 7, body = "are you there?"))
        // The cooldown restarts from that send.
        advance(5.seconds)
        assertEquals(NotSentUpdateTooSoon, decide(replacesId = 7, body = "hello??"))
    }

    @Test
    fun `an update from an app with nothing sent yet goes`() {
        // Nothing on the watch for it to update, e.g. the original came before coreapp started.
        assertEquals(SendToWatch, decide(replacesId = 7))
    }

    @Test
    fun `apps are tracked separately`() {
        assertEquals(SendToWatch, decide(app = "a_a_1"))
        assertEquals(SendToWatch, decide(app = "b_b_1", replacesId = 3, body = "other"))
    }

    @Test
    fun `filtered notifications don't start a cooldown`() {
        assertEquals(NotSentLowUrgency, decide(hints = mapOf("urgency" to 0)))
        assertEquals(SendToWatch, decide(replacesId = 7))
    }

    @Test
    fun `hints arrive as D-Bus variants and are unwrapped`() {
        val hints = mapOf<String, Variant<*>>("urgency" to Variant(0.toByte()), "transient" to Variant(true))
        assertEquals(mapOf("urgency" to 0.toByte(), "transient" to true), hints.unwrapHints())
    }
}
