package io.rebble.libpebblecommon.di

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationBridgeOwnerTest {
    @Test
    fun `the bridge gaining an owner means subscribe again`() {
        // A restart: the old process's unique name released, then a new one takes the name.
        assertTrue(bridgeOwnerGained("org.thomasredstone.NotificationBridge1", ":1.512"))
    }

    @Test
    fun `the bridge losing its owner does not`() {
        assertFalse(bridgeOwnerGained("org.thomasredstone.NotificationBridge1", ""))
        assertFalse(bridgeOwnerGained("org.thomasredstone.NotificationBridge1", null))
    }

    @Test
    fun `other names do not`() {
        assertFalse(bridgeOwnerGained("org.mpris.MediaPlayer2.sonicplayer", ":1.7"))
        assertFalse(bridgeOwnerGained(":1.512", ":1.512"))
        assertFalse(bridgeOwnerGained(null, ":1.512"))
    }
}
