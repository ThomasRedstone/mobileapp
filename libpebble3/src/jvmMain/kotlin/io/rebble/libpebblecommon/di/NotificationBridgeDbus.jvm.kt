package io.rebble.libpebblecommon.di

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * `dbus-java` plumbing for `../ut-notify`'s `org.thomasredstone.NotificationBridge1` - a
 * standalone, unconfined Rust/zbus daemon that eavesdrops `org.freedesktop.Notifications.Notify`
 * system-wide (something a confined Click structurally cannot do itself) and re-broadcasts
 * approved subscribers a kernel-verified `source_app_id` alongside each notification's original
 * arguments, unchanged. See ../ut-notify/docs/notification-bridge-api.md for the full contract -
 * this file's shape mirrors src/bridge_interface.rs there exactly, not a reinterpretation of it.
 *
 * Session bus, same reasoning as LomiriHistoryDbus.jvm.kt - this is a session-scoped daemon, not
 * a system one.
 */
internal const val NOTIFICATION_BRIDGE_OBJECT_PATH = "/org/thomasredstone/NotificationBridge1"
internal const val NOTIFICATION_BRIDGE_BUS_NAME = "org.thomasredstone.NotificationBridge1"

@DBusInterfaceName(NOTIFICATION_BRIDGE_BUS_NAME)
internal interface NotificationBridge1 : DBusInterface {
    fun Subscribe()
    fun Unsubscribe()

    // Mirrors org.freedesktop.Notifications.Notify's own argument list verbatim, with
    // source_app_id prepended - the contract's own wording, not paraphrased. hints/actions
    // are guaranteed to exist but their contents are entirely up to the source app; nothing here
    // should assume a specific key or action is present.
    class NotificationReceived(
        path: String,
        val sourceAppId: String,
        val appName: String,
        val replacesId: UInt,
        val appIcon: String,
        val summary: String,
        val body: String,
        val actions: List<String>,
        val hints: Map<String, Variant<*>>,
        val expireTimeout: Int,
    ) : DBusSignal(
        path, sourceAppId, appName, replacesId, appIcon, summary, body, actions, hints,
        expireTimeout,
    )
}
