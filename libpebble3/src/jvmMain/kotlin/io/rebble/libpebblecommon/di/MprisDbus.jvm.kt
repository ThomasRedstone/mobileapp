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
