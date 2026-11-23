package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * `dbus-java` plumbing for MPRIS (`org.mpris.MediaPlayer2.*`) - the standard Linux/UT interface
 * media players expose on the session bus. Unlike `com.lomiri.HistoryService` or
 * `org.thomasredstone.NotificationBridge1` (fixed, well-known bus names), an MPRIS player's bus
 * name isn't fixed - discovered via `org.freedesktop.DBus.ListNames`/`NameOwnerChanged`, see
 * `LinuxSystemMusicControl` in LinuxPlatformServices.kt.
 *
 * Verified live against Sonic Player (`org.mpris.MediaPlayer2.sonicplayer`), the only real MPRIS
 * server on this fleet: `busctl --user introspect org.mpris.MediaPlayer2.sonicplayer
 * /org/mpris/MediaPlayer2`.
 */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
internal interface MprisPlayer : DBusInterface {
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Next()
    fun Previous()
}

// MPRIS metadata is a{sv} on the "Metadata" property (read via org.freedesktop.DBus.Properties,
// not a method on this interface) - xesam:artist is an array of strings (an MPRIS player can
// report multiple artists), everything else is a scalar. mpris:length is microseconds.
internal fun Map<String, Variant<*>>.mprisString(key: String): String? = this[key]?.value as? String

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Variant<*>>.mprisArtist(): String? {
    val artists = this["xesam:artist"]?.value as? List<*> ?: return null
    return artists.filterIsInstance<String>().joinToString(", ").takeIf { it.isNotEmpty() }
}

internal fun Map<String, Variant<*>>.mprisLong(key: String): Long? = when (val v = this[key]?.value) {
    is Number -> v.toLong()
    else -> null
}

internal fun Map<String, Variant<*>>.mprisInt(key: String): Int? = when (val v = this[key]?.value) {
    is Number -> v.toInt()
    else -> null
}

/**
 * Ubuntu Touch's media-hub always owns an MPRIS name and always reads Stopped with no track, so
 * it's never "the player the watch should control" - picking it makes every press a no-op.
 * Same exclusion linux-auto's userd makes (userdgo/mpris.go, bench 2026-09-06).
 */
internal const val MEDIA_HUB_BUS_NAME = "org.mpris.MediaPlayer2.MediaHub"

/** The MPRIS player the watch should follow, from a `ListNames` answer: [preferred] if it's on
 *  the bus, otherwise the first real player, otherwise none. */
internal fun chooseMprisPlayer(names: List<String>, preferred: String): String? {
    val players = names.filter { it.startsWith("org.mpris.MediaPlayer2.") && it != MEDIA_HUB_BUS_NAME }
    return players.firstOrNull { it == preferred } ?: players.firstOrNull()
}

/** What a play press from the watch turns into on the bus. */
internal enum class MprisPlayCommand {
    /** No player is running: call Play on the preferred player's well-known name, which
     *  D-Bus-activates it (Rack starts a shuffle when Play arrives with nothing queued). */
    WakePreferred,
    /** Send Play to the active player. */
    Play,
    /** Send PlayPause to the active player. */
    PlayPause,
}

/**
 * Picks the command for a play/pause press. [activePlayer] is the player being followed (null if
 * none), [rawStatus] its MPRIS `PlaybackStatus` as read just now. A Stopped player gets Play,
 * not the toggle: PlayPause on a stopped Rack is a no-op (linux-auto bench 2026-09-06), and Play
 * on a stopped Rack with an empty queue starts the shuffle. [toggle] is false for an explicit
 * play press, which never pauses.
 */
internal fun mprisPlayCommand(activePlayer: String?, rawStatus: String?, toggle: Boolean): MprisPlayCommand = when {
    activePlayer == null -> MprisPlayCommand.WakePreferred
    !toggle || rawStatus == "Stopped" -> MprisPlayCommand.Play
    else -> MprisPlayCommand.PlayPause
}
