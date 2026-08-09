package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class DbusGattClientTest {
    @Test
    fun `empty flags produce no bits`() {
        assertEquals(0, emptyList<String>().asPropertiesBitmask())
    }

    @Test
    fun `each BlueZ flag maps to its Android-style bit`() {
        assertEquals(0x01, listOf("broadcast").asPropertiesBitmask())
        assertEquals(0x02, listOf("read").asPropertiesBitmask())
        assertEquals(0x04, listOf("write-without-response").asPropertiesBitmask())
        assertEquals(0x08, listOf("write").asPropertiesBitmask())
        assertEquals(0x10, listOf("notify").asPropertiesBitmask())
        assertEquals(0x20, listOf("indicate").asPropertiesBitmask())
        assertEquals(0x40, listOf("authenticated-signed-writes").asPropertiesBitmask())
        assertEquals(0x80, listOf("extended-properties").asPropertiesBitmask())
    }

    @Test
    fun `flags combine into a single bitmask, matching a real Pebble characteristic`() {
        // The PPoG data characteristic BlueZ actually reports on a Pebble Time 2.
        assertEquals(0x0C, listOf("write", "write-without-response").asPropertiesBitmask())
    }

    @Test
    fun `BlueZ security flags with no Android bit equivalent are ignored, not errors`() {
        assertEquals(
            0x02,
            listOf("read", "encrypt-read", "secure-read").asPropertiesBitmask()
        )
    }

    @Test
    fun `unknown flags are ignored`() {
        assertEquals(0, listOf("some-future-bluez-flag").asPropertiesBitmask())
    }
}
