package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shells out to `busctl` for real BlueZ D-Bus access on Linux/Ubuntu Touch.
 *
 * Not the long-term shape: `dbus-java`'s `EXTERNAL` SASL auth sends the wrong
 * UID on this platform (traced at the syscall level — it authenticates as
 * UID 0 instead of the real process UID, so BlueZ rejects it; a genuine
 * upstream bug, not anything specific to this app). `busctl` was proven
 * reliable throughout the Ubuntu Touch PoC investigation
 * (docs/ubuntu-touch-poc-plan.md) and unblocks real implementation now;
 * swap this for a proper D-Bus binding once that's fixed or replaced.
 */
private val logger = Logger.withTag("BusctlDbus")

internal object BusctlDbus {
    private const val TIMEOUT_SECONDS = 15L

    // Libertine's bwrap sandbox uses --tmpfs /run, which wipes /run/dbus - only
    // /run/user/<uid> is bind-mounted back in. dbus_proxy.py (run on the real host, outside
    // the sandbox) forwards a socket placed there to the real system bus, so this is reachable
    // from inside. Falls back to the real socket directly when it exists (e.g. unsandboxed use).
    private val dbusSystemBusAddress: String? by lazy {
        val realSocket = File("/run/dbus/system_bus_socket")
        val xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR")
        val proxySocket = xdgRuntimeDir?.let { File(it, "dbus-system-proxy.sock") }
        when {
            realSocket.exists() -> "unix:path=${realSocket.absolutePath}"
            proxySocket?.exists() == true -> "unix:path=${proxySocket.absolutePath}"
            else -> null
        }
    }

    fun call(vararg args: String): String? = run("busctl", "--system", "call", *args)

    fun getProperty(service: String, path: String, iface: String, property: String): String? =
        run("busctl", "--system", "get-property", service, path, iface, property)

    fun getManagedObjects(): String? = call(
        "org.bluez", "/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects"
    )

    private fun run(vararg cmd: String): String? {
        return try {
            val builder = ProcessBuilder(*cmd).redirectErrorStream(false)
            dbusSystemBusAddress?.let { builder.environment()["DBUS_SYSTEM_BUS_ADDRESS"] = it }
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.e { "busctl call timed out: ${cmd.joinToString(" ")}" }
                return null
            }
            if (process.exitValue() != 0) {
                logger.e { "busctl call failed (${process.exitValue()}): ${cmd.joinToString(" ")} / $stderr" }
                return null
            }
            stdout
        } catch (e: Exception) {
            logger.e(e) { "busctl call threw: ${cmd.joinToString(" ")}" }
            null
        }
    }
}

/**
 * A single `org.bluez.Device1` object parsed out of `GetManagedObjects`'
 * plain-text (`busctl` default varlink-ish) output. Deliberately tolerant —
 * BlueZ's dbus property dump is not trivial to fully grammar-parse from
 * shell text, so this extracts just the fields the scanner needs via
 * per-object regex matching rather than a full GVariant parser.
 */
internal data class BluezDevice(
    val path: String,
    val address: String?,
    val name: String?,
    val rssi: Int?,
    val bonded: Boolean,
    val manufacturerData: Map<Int, ByteArray>,
)

internal object BluezObjectParser {
    // Matches one `/org/bluez/hci0/dev_XX_XX_.../serviceNNNN` style path and
    // captures everything up to the next top-level object path, so property
    // extraction below can be scoped per-device.
    private val devicePathRegex = Regex(""""(/org/bluez/hci\d+/dev_[0-9A-Fa-f_]+)"\s+\d+\s+"org\.bluez\.Device1"""")
    private val addressRegex = Regex(""""Address"\s+s\s+"([0-9A-Fa-f:]+)"""")
    private val nameRegex = Regex(""""Name"\s+s\s+"([^"]*)"""")
    private val rssiRegex = Regex(""""RSSI"\s+n\s+(-?\d+)""")
    private val bondedRegex = Regex(""""Bonded"\s+b\s+(true|false)""")
    // busctl prints ManufacturerData as: "ManufacturerData" a{qv} <count> <code> ay <len> <b0> <b1> ...
    private val manufacturerDataBlockRegex = Regex(""""ManufacturerData"\s+a\{qv\}\s+(\d+)\s+(.*)""")

    fun parse(managedObjectsText: String): List<BluezDevice> {
        val matches = devicePathRegex.findAll(managedObjectsText).toList()
        return matches.mapIndexed { index, match ->
            val path = match.groupValues[1]
            val start = match.range.last
            val end = matches.getOrNull(index + 1)?.range?.first ?: managedObjectsText.length
            val block = managedObjectsText.substring(start, end)
            BluezDevice(
                path = path,
                address = addressRegex.find(block)?.groupValues?.get(1),
                name = nameRegex.find(block)?.groupValues?.get(1),
                rssi = rssiRegex.find(block)?.groupValues?.get(1)?.toIntOrNull(),
                bonded = bondedRegex.find(block)?.groupValues?.get(1) == "true",
                manufacturerData = parseManufacturerData(block),
            )
        }
    }

    private fun parseManufacturerData(block: String): Map<Int, ByteArray> {
        val match = manufacturerDataBlockRegex.find(block) ?: return emptyMap()
        val count = match.groupValues[1].toIntOrNull() ?: return emptyMap()
        val tokens = match.groupValues[2].trim().split(Regex("\\s+"))
        val result = mutableMapOf<Int, ByteArray>()
        var i = 0
        repeat(count) {
            if (i >= tokens.size) return@repeat
            val code = tokens[i].toIntOrNull() ?: return@repeat
            i++
            if (i >= tokens.size || tokens[i] != "ay") return@repeat
            i++
            val len = tokens.getOrNull(i)?.toIntOrNull() ?: return@repeat
            i++
            val bytes = ByteArray(len)
            for (b in 0 until len) {
                bytes[b] = (tokens.getOrNull(i + b)?.toIntOrNull() ?: 0).toByte()
            }
            i += len
            result[code] = bytes
        }
        return result
    }
}
