package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LomiriHistoryDbusTest {
    @Test
    fun `int reads raw Integer value without ClassCastException`() {
        // This reproduces b-i3im: dbus-java gives raw Integer, not Variant<Int>
        val map: Map<String, Any> = mapOf("type" to 0)
        assertEquals(0, map.int("type"))
    }

    @Test
    fun `int reads Variant Integer value`() {
        val map = mapOf("type" to Variant(0))
        assertEquals(0, map.int("type"))
    }

    @Test
    fun `string reads raw String value`() {
        val map: Map<String, Any> = mapOf("senderId" to "+447123456789")
        assertEquals("+447123456789", map.string("senderId"))
    }

    @Test
    fun `string reads Variant String value`() {
        val map = mapOf("senderId" to Variant("+447123456789"))
        assertEquals("+447123456789", map.string("senderId"))
    }

    @Test
    fun `long reads raw Number and Variant Number`() {
        val rawMap: Map<String, Any> = mapOf("duration" to 120)
        assertEquals(120L, rawMap.long("duration"))

        val variantMap = mapOf("duration" to Variant(120L))
        assertEquals(120L, variantMap.long("duration"))
    }

    @Test
    fun `bool reads raw and Variant Booleans`() {
        val rawMap: Map<String, Any> = mapOf("newEvent" to true)
        assertTrue(rawMap.bool("newEvent") == true)

        val variantMap = mapOf("newEvent" to Variant(false))
        assertFalse(variantMap.bool("newEvent") == true)

        val intBoolMap: Map<String, Any> = mapOf("newEvent" to 1)
        assertTrue(intBoolMap.bool("newEvent") == true)
    }

    @Test
    fun `toHistoryTextEvent parses raw event map from HistoryService EventsAdded`() {
        val rawEvent: Map<String, Any> = mapOf(
            "type" to 0,
            "senderId" to "+447123456789",
            "message" to "Hello from SMS",
            "timestamp" to "2026-10-31T21:00:00Z",
            "newEvent" to true,
            "participants" to listOf(
                mapOf("alias" to "Tom")
            ),
        )

        val event = rawEvent.toHistoryTextEvent()
        assertNotNull(event)
        assertEquals("+447123456789", event.senderId)
        assertEquals("Tom", event.senderName)
        assertEquals("Hello from SMS", event.message)
        assertEquals("2026-10-31T21:00:00Z", event.timestampIso)
        assertTrue(event.newEvent)
    }

    @Test
    fun `toHistoryTextEvent parses Variant-wrapped event map`() {
        val variantEvent = mapOf(
            "type" to Variant(0),
            "senderId" to Variant("+447123456789"),
            "message" to Variant("Hello from SMS"),
            "timestamp" to Variant("2026-10-31T21:00:00Z"),
            "newEvent" to Variant(true),
            "participants" to Variant(
                listOf(
                    mapOf("alias" to Variant("Tom"))
                ),
                "aa{sv}"
            ),
        )

        val event = variantEvent.toHistoryTextEvent()
        assertNotNull(event)
        assertEquals("+447123456789", event.senderId)
        assertEquals("Tom", event.senderName)
        assertEquals("Hello from SMS", event.message)
        assertEquals("2026-10-31T21:00:00Z", event.timestampIso)
        assertTrue(event.newEvent)
    }

    @Test
    fun `toHistoryCallEvent parses raw call event with duration and missed status`() {
        val rawEvent: Map<String, Any> = mapOf(
            "type" to 1,
            "senderId" to "+447987654321",
            "timestamp" to "2026-10-31T21:05:00Z",
            "duration" to 0,
            "missed" to 1,
            "participants" to listOf(
                mapOf("alias" to "Caller")
            ),
        )

        val call = rawEvent.toHistoryCallEvent()
        assertNotNull(call)
        assertEquals("+447987654321", call.senderId)
        assertEquals("Caller", call.callerName)
        assertEquals("2026-10-31T21:05:00Z", call.timestampIso)
        assertEquals(0L, call.durationSeconds)
        assertTrue(call.missed)
    }

    @Test
    fun `missing fields return null gracefully without throwing`() {
        val empty: Map<String, Any> = emptyMap()
        assertNull(empty.string("missing"))
        assertNull(empty.int("missing"))
        assertNull(empty.long("missing"))
        assertNull(empty.bool("missing"))
        assertNull(empty.toHistoryTextEvent())
        assertNull(empty.toHistoryCallEvent())
    }
}
