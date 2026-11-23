package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnScreenFeedbackTest {
    @Test
    fun `the volume bubble is on-screen feedback`() {
        // What ayatana-indicator-sound sends on every volume step.
        assertTrue(
            isOnScreenFeedback(
                mapOf(
                    "x-lomiri-private-synchronous" to Variant("true"),
                    "x-lomiri-non-shaped-icon" to Variant("true"),
                ),
            ),
        )
    }

    @Test
    fun `the pre-Lomiri hint name counts too, whatever its value`() {
        assertTrue(isOnScreenFeedback(mapOf("x-canonical-private-synchronous" to Variant(""))))
    }

    @Test
    fun `ordinary notifications are not`() {
        assertFalse(isOnScreenFeedback(emptyMap<String, Variant<*>>()))
        assertFalse(
            isOnScreenFeedback(
                mapOf("urgency" to Variant(1.toByte()), "x-lomiri-snap-decisions" to Variant("true")),
            ),
        )
    }
}
