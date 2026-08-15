package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.NotConnected
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.types.Variant
import java.io.File

/**
 * Shared `dbus-java` plumbing for talking to BlueZ over the system bus - the only viable path
 * under Click confinement, where subprocess exec of system binaries (including `busctl`) is
 * denied even though the D-Bus access itself is granted by the `bluetooth` AppArmor policy
 * group.
 */

/** The real process UID, read from the kernel rather than trusted from Java (which has no
 *  portable API for it). dbus-java's own EXTERNAL SASL auto-detection sends UID 0 in this
 *  sandboxed environment - a real upstream bug, not anything specific to this app - so the UID
 *  has to be supplied explicitly (`withSaslUid`, dbus-java PR #178). */
internal fun currentUnixUid(): Long? = try {
    File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("Uid:") }
        ?.split(Regex("\\s+"))
        ?.getOrNull(1)
        ?.toLong()
} catch (e: Exception) {
    null
}

// The real, global per-method-call reply-wait timeout - MethodCall.setDefaultTimeout(), not
// TransportConfig.withTimeout() (which only affects connection setup, not method call replies;
// confirmed empirically - a 60s TransportConfig value still produced a NoReply after ~20s, dbus-
// java's own hardcoded default). This is a static/JVM-wide setting, so every caller here shares
// one generous value rather than each passing its own (whichever call happens last would win and
// silently override the others otherwise). 60s comfortably covers both Device1.Connect() (BlueZ
// blocks until the link comes up or fails) and Device1.Pair() (BlueZ blocks until the watch's own
// pairing prompt is actually answered by a human).
private const val DBUS_REPLY_TIMEOUT_MS = 60_000L

internal fun buildSystemBusConnection(): DBusConnection {
    MethodCall.setDefaultTimeout(DBUS_REPLY_TIMEOUT_MS)
    val builder = DBusConnectionBuilder.forSystemBus()
    currentUnixUid()?.let { uid ->
        builder.transportConfig().configureSasl().withSaslUid(uid).back()
    }
    return builder.build()
}

/**
 * A [buildSystemBusConnection] held for longer than a single call/attempt (a `by lazy` singleton,
 * or reused across loop iterations) can die permanently mid-lifetime - confirmed live, twice, in
 * one session: [io.rebble.libpebblecommon.connection.bt.Pairing.jvm.kt]'s old `by lazy` connection
 * hit this first (every `Pair()` after that point failed with NotConnected, forever, since a
 * `by lazy` value only builds once), then BluetoothState.jvm.kt's poll loop hit the identical
 * failure the very next test run. Wrap any such long-lived connection in this instead of holding
 * a bare `DBusConnection` - [withConnection] rebuilds once and retries on NotConnected rather than
 * failing forever on a connection that's never coming back.
 */
internal class SelfHealingSystemBusConnection {
    @Volatile
    private var connection: DBusConnection = buildSystemBusConnection()

    @Synchronized
    private fun rebuild(): DBusConnection {
        runCatching { connection.disconnect() }
        return buildSystemBusConnection().also { connection = it }
    }

    fun <T> withConnection(block: (DBusConnection) -> T): T =
        try {
            block(connection)
        } catch (e: NotConnected) {
            block(rebuild())
        }

    fun disconnect() {
        runCatching { connection.disconnect() }
    }
}

@DBusInterfaceName("org.bluez.Adapter1")
internal interface Adapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
}

@DBusInterfaceName("org.bluez.Device1")
internal interface Device1 : DBusInterface {
    fun Connect()
    fun Disconnect()
    fun Pair()
}

internal data class BluezDevice(
    val path: String,
    val address: String?,
    val name: String?,
    val rssi: Int?,
    val bonded: Boolean,
    val manufacturerData: Map<Int, ByteArray>,
)

// dbus-java decodes nested collection types buried inside a fully generic Variant<?> (as opposed
// to a concretely-typed method return) more loosely than a real ByteArray/UInt16 - defensive
// coercion here rather than a direct cast, which silently drops every entry if the runtime
// representation isn't exactly what's expected (confirmed empirically: BlueZ had real, fresh
// ManufacturerData for a live device and this was still returning an empty map).
private fun Any?.asIntKey(): Int? = when (this) {
    is Number -> toInt()
    else -> null
}

private fun Any?.asByteArray(): ByteArray? = when (this) {
    is ByteArray -> this
    is Array<*> -> ByteArray(size) { i -> (this[i] as? Number)?.toByte() ?: return null }
    is Collection<*> -> ByteArray(size) { i -> (elementAt(i) as? Number)?.toByte() ?: return null }
    else -> null
}

internal fun Map<DBusPath, Map<String, Map<String, Variant<*>>>>.parseBluezDevices(): List<BluezDevice> =
    mapNotNull { (path, ifaces) ->
        val device1 = ifaces["org.bluez.Device1"] ?: return@mapNotNull null
        val manufacturerData = (device1["ManufacturerData"]?.value as? Map<*, *>)
            ?.mapNotNull { (code, variant) ->
                val key = code.asIntKey() ?: return@mapNotNull null
                val bytes = ((variant as? Variant<*>)?.value ?: variant).asByteArray() ?: return@mapNotNull null
                key to bytes
            }
            ?.toMap()
            ?: emptyMap()
        BluezDevice(
            path = path.path,
            address = device1["Address"]?.value as? String,
            name = device1["Name"]?.value as? String,
            rssi = device1["RSSI"]?.value.asIntKey(),
            bonded = device1["Bonded"]?.value as? Boolean ?: false,
            manufacturerData = manufacturerData,
        )
    }
