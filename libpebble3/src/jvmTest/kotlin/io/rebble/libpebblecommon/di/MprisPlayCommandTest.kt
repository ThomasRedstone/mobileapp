package io.rebble.libpebblecommon.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MprisPlayCommandTest {
    private val sonic = "org.mpris.MediaPlayer2.sonicplayer"

    @Test
    fun `no player running wakes the preferred player, for play and for the toggle`() {
        assertEquals(MprisPlayCommand.WakePreferred, mprisPlayCommand(null, null, toggle = true))
        assertEquals(MprisPlayCommand.WakePreferred, mprisPlayCommand(null, null, toggle = false))
    }

    @Test
    fun `a stopped player gets Play, not the toggle that would do nothing`() {
        assertEquals(MprisPlayCommand.Play, mprisPlayCommand(sonic, "Stopped", toggle = true))
    }

    @Test
    fun `a playing or paused player gets the toggle`() {
        assertEquals(MprisPlayCommand.PlayPause, mprisPlayCommand(sonic, "Playing", toggle = true))
        assertEquals(MprisPlayCommand.PlayPause, mprisPlayCommand(sonic, "Paused", toggle = true))
    }

    @Test
    fun `an explicit play never toggles`() {
        assertEquals(MprisPlayCommand.Play, mprisPlayCommand(sonic, "Playing", toggle = false))
        assertEquals(MprisPlayCommand.Play, mprisPlayCommand(sonic, "Paused", toggle = false))
    }

    @Test
    fun `an unreadable status still toggles a player that is there`() {
        assertEquals(MprisPlayCommand.PlayPause, mprisPlayCommand(sonic, null, toggle = true))
    }

    @Test
    fun `media-hub alone on the bus is no player at all`() {
        assertNull(chooseMprisPlayer(listOf("org.freedesktop.DBus", MEDIA_HUB_BUS_NAME), sonic))
    }

    @Test
    fun `the preferred player wins over media-hub and others`() {
        val names = listOf(MEDIA_HUB_BUS_NAME, "org.mpris.MediaPlayer2.other", sonic, "org.mpris.MediaPlayer2.rack")
        assertEquals(sonic, chooseMprisPlayer(names, sonic))
    }

    @Test
    fun `another real player is followed when the preferred one is absent`() {
        val names = listOf(MEDIA_HUB_BUS_NAME, "org.mpris.MediaPlayer2.other")
        assertEquals("org.mpris.MediaPlayer2.other", chooseMprisPlayer(names, sonic))
    }
}
