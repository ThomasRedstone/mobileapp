package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MprisDbusTest {
    @Test
    fun `mprisString reads a plain string value`() {
        val metadata = mapOf("xesam:title" to Variant<Any>("Song Title"))
        assertEquals("Song Title", metadata.mprisString("xesam:title"))
    }

    @Test
    fun `mprisString returns null for a missing key`() {
        assertNull(emptyMap<String, Variant<*>>().mprisString("xesam:title"))
    }

    // dbus-java's Variant(value) constructor validates the value is an exportable D-Bus type at
    // construction and rejects a plain Kotlin/Java List with no declared element signature - real
    // values arriving off the wire don't go through that same check. The (value, signature) form
    // sidesteps it, matching how a real "as" (array-of-string) property value would be typed.
    private fun stringArrayVariant(values: List<String>) = Variant<Any>(values, "as")

    @Test
    fun `mprisArtist joins a multi-artist array, the real MPRIS shape`() {
        // xesam:artist is always an array in the MPRIS spec, even for a single artist -
        // this is the shape that motivated the dedicated PropertiesChanged rewrite in
        // finding-adjacent work elsewhere in this session (arrays, not scalars, for artist).
        val metadata = mapOf("xesam:artist" to stringArrayVariant(listOf("Artist One", "Artist Two")))
        assertEquals("Artist One, Artist Two", metadata.mprisArtist())
    }

    @Test
    fun `mprisArtist handles a single-artist array`() {
        val metadata = mapOf("xesam:artist" to stringArrayVariant(listOf("Solo Artist")))
        assertEquals("Solo Artist", metadata.mprisArtist())
    }

    @Test
    fun `mprisArtist returns null for an empty artist array`() {
        val metadata = mapOf("xesam:artist" to stringArrayVariant(emptyList()))
        assertNull(metadata.mprisArtist())
    }

    @Test
    fun `mprisArtist returns null when the key is missing entirely`() {
        assertNull(emptyMap<String, Variant<*>>().mprisArtist())
    }

    @Test
    fun `mprisLong reads mpris-length in microseconds`() {
        val metadata = mapOf("mpris:length" to Variant<Any>(180_000_000L))
        assertEquals(180_000_000L, metadata.mprisLong("mpris:length"))
    }

    @Test
    fun `mprisLong accepts a narrower integer type too`() {
        // dbus-java doesn't always hand back Long even when the D-Bus signature is int64 -
        // the numeric-coercion pattern here mirrors BluezDbus.jvm.kt's asIntKey/asByteArray.
        val metadata = mapOf("mpris:length" to Variant<Any>(180_000_000))
        assertEquals(180_000_000L, metadata.mprisLong("mpris:length"))
    }

    @Test
    fun `mprisLong returns null for a non-numeric value`() {
        val metadata = mapOf("mpris:length" to Variant<Any>("not a number"))
        assertNull(metadata.mprisLong("mpris:length"))
    }

    @Test
    fun `mprisInt reads xesam-trackNumber`() {
        val metadata = mapOf("xesam:trackNumber" to Variant<Any>(7))
        assertEquals(7, metadata.mprisInt("xesam:trackNumber"))
    }

    @Test
    fun `mprisInt returns null for a missing key`() {
        assertNull(emptyMap<String, Variant<*>>().mprisInt("xesam:trackNumber"))
    }
}
