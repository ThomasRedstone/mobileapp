package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt16
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
private fun currentUnixUid(): Long? = try {
    File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("Uid:") }
        ?.split(Regex("\\s+"))
        ?.getOrNull(1)
        ?.toLong()
} catch (e: Exception) {
    null
}

internal fun buildSystemBusConnection(replyTimeoutMs: Int = 15_000): DBusConnection {
    val builder = DBusConnectionBuilder.forSystemBus()
    currentUnixUid()?.let { uid ->
        builder.transportConfig().configureSasl().withSaslUid(uid).back()
    }
    builder.transportConfig().withTimeout(replyTimeoutMs)
    return builder.build()
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

internal fun Map<DBusPath, Map<String, Map<String, Variant<*>>>>.parseBluezDevices(): List<BluezDevice> =
    mapNotNull { (path, ifaces) ->
        val device1 = ifaces["org.bluez.Device1"] ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val manufacturerData = (device1["ManufacturerData"]?.value as? Map<UInt16, Variant<*>>)
            ?.mapNotNull { (code, variant) -> (variant.value as? ByteArray)?.let { code.toInt() to it } }
            ?.toMap()
            ?: emptyMap()
        BluezDevice(
            path = path.path,
            address = device1["Address"]?.value as? String,
            name = device1["Name"]?.value as? String,
            rssi = (device1["RSSI"]?.value as? Short)?.toInt(),
            bonded = device1["Bonded"]?.value as? Boolean ?: false,
            manufacturerData = manufacturerData,
        )
    }
