package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnectionResult
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Real GATT client transport for Linux/Ubuntu Touch, talking to BlueZ directly over D-Bus via
 * `dbus-java`. Replaces Kable/`kable-btleplug-ffi` on this platform: that Rust/JNI bridge never
 * issues a single D-Bus call towards the watch in this sandboxed environment (traced with a
 * byte-level D-Bus relay - every other layer it depends on, proven working directly the same
 * night: the two-hop proxy, bonding, BlueZ itself), so its 60s Kotlin-side watchdog just times
 * out waiting for a connection attempt that never happened. See docs/ubuntu-touch-poc-plan.md,
 * "Kable/btleplug never actually attempts the connection".
 */
private val logger = Logger.withTag("DbusGattClient")

private fun bluezDevicePath(identifier: PebbleBleIdentifier): String =
    "/org/bluez/hci0/dev_" + identifier.asString.replace(":", "_")

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
    logger.e(e) { "couldn't read real uid from /proc/self/status" }
    null
}

private fun buildSystemBusConnection(): DBusConnection {
    val builder = DBusConnectionBuilder.forSystemBus()
    currentUnixUid()?.let { uid ->
        builder.transportConfig().configureSasl().withSaslUid(uid).back()
    }
    return builder.build()
}

@DBusInterfaceName("org.bluez.Device1")
private interface Device1 : DBusInterface {
    fun Connect()
    fun Disconnect()
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
private interface GattCharacteristic1 : DBusInterface {
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}

class DbusGattConnector(
    private val identifier: PebbleBleIdentifier,
    private val scope: ConnectionCoroutineScope,
    private val blePlatformConfig: BlePlatformConfig,
) : GattConnector {
    private val logger = Logger.withTag("DbusGattConnector/${identifier.asString}")
    private val devicePath = bluezDevicePath(identifier)

    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    private var connection: DBusConnection? = null
    private var attemptedConnection = false

    override suspend fun connect(): GattConnectionResult {
        val conn = try {
            buildSystemBusConnection()
        } catch (e: DBusException) {
            logger.e(e) { "couldn't connect to system bus" }
            _disconnected.complete(ConnectionFailureReason.FailedToConnect)
            return GattConnectionResult.Failure(ConnectionFailureReason.FailedToConnect)
        }
        connection = conn

        conn.addSigHandler(Properties.PropertiesChanged::class.java, DBusSigHandler { signal ->
            if (signal.path != devicePath || signal.interfaceName != "org.bluez.Device1") return@DBusSigHandler
            val connectedNow = signal.propertiesChanged["Connected"]?.value as? Boolean
            if (connectedNow == false && !_disconnected.isCompleted) {
                logger.i { "Disconnection (PropertiesChanged Connected=false)" }
                _disconnected.complete(ConnectionFailureReason.FailedToConnect)
            }
        })

        var timedOut = false
        val connectTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT)
            timedOut = true
            logger.w { "Connect timeout — force-disconnecting peripheral" }
            runCatching { conn.getRemoteObject("org.bluez", devicePath, Device1::class.java).Disconnect() }
        }
        return try {
            attemptedConnection = true
            val device = conn.getRemoteObject("org.bluez", devicePath, Device1::class.java)
            device.Connect()
            val resolved = withTimeoutOrNull(SERVICES_RESOLVED_TIMEOUT) {
                waitForServicesResolved(conn)
            }
            if (resolved != true) {
                logger.w { "Connect() returned but services never resolved" }
                runCatching { device.Disconnect() }
                GattConnectionResult.Failure(ConnectionFailureReason.ConnectTimeout)
            } else {
                GattConnectionResult.Success(
                    DbusConnectedGattClient(identifier, conn, devicePath, blePlatformConfig)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "error connecting" }
            if (timedOut) {
                GattConnectionResult.Failure(ConnectionFailureReason.ConnectTimeout)
            } else {
                GattConnectionResult.Failure(ConnectionFailureReason.FailedToConnect)
            }
        } finally {
            connectTimeoutJob.cancel()
        }
    }

    private suspend fun waitForServicesResolved(conn: DBusConnection): Boolean {
        val props = conn.getRemoteObject("org.bluez", devicePath, Properties::class.java)
        while (true) {
            val resolved = props.Get<Boolean>("org.bluez.Device1", "ServicesResolved")
            if (resolved == true) return true
            kotlinx.coroutines.delay(POLL_INTERVAL)
        }
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()..." }
        val conn = connection
        if (conn != null) {
            runCatching { conn.getRemoteObject("org.bluez", devicePath, Device1::class.java).Disconnect() }
            runCatching { conn.disconnect() }
        }
        if (!attemptedConnection && !_disconnected.isCompleted) {
            _disconnected.complete(ConnectionFailureReason.NotAnError_NeverAttmpedConnection)
        }
        logger.d { "/disconnect()..." }
    }

    override fun close() {
        runCatching { connection?.disconnect() }
    }

    companion object {
        private val CONNECT_TIMEOUT = 60.seconds
        private val SERVICES_RESOLVED_TIMEOUT = 15.seconds
        private val POLL_INTERVAL = 200.milliseconds
    }
}

class DbusConnectedGattClient(
    private val identifier: PebbleBleIdentifier,
    private val connection: DBusConnection,
    private val devicePath: String,
    private val blePlatformConfig: BlePlatformConfig,
) : ConnectedGattClient {
    private val logger = Logger.withTag("DbusConnectedGattClient-${identifier.asString}")
    private val servicesLock = Mutex()
    private var discoveredServices: List<GattService>? = null
    private val startedNotify = mutableSetOf<String>()

    override suspend fun discoverServices(): Boolean = servicesLock.withLock {
        try {
            discoveredServices = fetchServices()
            true
        } catch (e: Exception) {
            logger.e(e) { "discoverServices failed" }
            false
        }
    }

    private fun fetchServices(): List<GattService> {
        val objectManager = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
        val objects = objectManager.GetManagedObjects()
        val underDevice = objects.filterKeys { it.path.startsWith("$devicePath/") }

        val servicePaths = underDevice
            .filter { (_, ifaces) -> ifaces.containsKey("org.bluez.GattService1") }
            .mapNotNull { (path, ifaces) ->
                val uuid = (ifaces["org.bluez.GattService1"]?.get("UUID")?.value as? String) ?: return@mapNotNull null
                path.path to uuid
            }

        // Characteristic D-Bus paths aren't part of the commonMain GattCharacteristic shape (every
        // other platform identifies characteristics by UUID alone), so findCharacteristicPath()
        // below re-derives them from GetManagedObjects again rather than threading an extra field
        // through GattService/GattCharacteristic for every platform.
        return servicePaths.map { (servicePath, serviceUuid) ->
            val characteristics = underDevice
                .filter { (path, ifaces) ->
                    path.path.startsWith("$servicePath/") && ifaces.containsKey("org.bluez.GattCharacteristic1")
                }
                .mapNotNull { (_, ifaces) ->
                    val charProps = ifaces["org.bluez.GattCharacteristic1"] ?: return@mapNotNull null
                    val uuid = charProps["UUID"]?.value as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val flags = (charProps["Flags"]?.value as? List<String>) ?: emptyList()
                    val propertiesBitmask = flags.asPropertiesBitmask()
                    GattCharacteristic(
                        uuid = Uuid.parse(uuid),
                        properties = propertiesBitmask,
                        permissions = propertiesBitmask,
                        descriptors = emptyList(),
                    )
                }
            GattService(uuid = Uuid.parse(serviceUuid), characteristics = characteristics)
        }
    }

    private fun findCharacteristicPath(serviceUuid: Uuid, characteristicUuid: Uuid): String? {
        val objectManager = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
        val objects = objectManager.GetManagedObjects()
        val servicePath = objects.entries.firstOrNull { (path, ifaces) ->
            path.path.startsWith("$devicePath/") &&
                (ifaces["org.bluez.GattService1"]?.get("UUID")?.value as? String)
                    ?.let { Uuid.parse(it) } == serviceUuid
        }?.key?.path ?: return null
        return objects.entries.firstOrNull { (path, ifaces) ->
            path.path.startsWith("$servicePath/") &&
                (ifaces["org.bluez.GattCharacteristic1"]?.get("UUID")?.value as? String)
                    ?.let { Uuid.parse(it) } == characteristicUuid
        }?.key?.path
    }

    override fun subscribeToCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        onSubscription: (suspend () -> Unit)?,
    ): Flow<ByteArray>? {
        val path = findCharacteristicPath(serviceUuid, characteristicUuid)
        if (path == null) {
            logger.e { "couldn't find characteristic: $characteristicUuid" }
            return null
        }
        return callbackFlow {
            val handler = DBusSigHandler<Properties.PropertiesChanged> { signal ->
                if (signal.path != path || signal.interfaceName != "org.bluez.GattCharacteristic1") return@DBusSigHandler
                val value = signal.propertiesChanged["Value"]?.value as? ByteArray ?: return@DBusSigHandler
                trySend(value)
            }
            connection.addSigHandler(Properties.PropertiesChanged::class.java, handler)
            if (startedNotify.add(path)) {
                runCatching {
                    connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java).StartNotify()
                }.onFailure { logger.e(it) { "StartNotify failed for $path" } }
            }
            onSubscription?.invoke()
            awaitClose {
                connection.removeSigHandler(Properties.PropertiesChanged::class.java, handler)
                startedNotify.remove(path)
                runCatching {
                    connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java).StopNotify()
                }
            }
        }
    }

    override suspend fun isBonded(): Boolean = io.rebble.libpebblecommon.connection.bt.isBonded(identifier)

    override suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType,
    ): Boolean {
        val path = findCharacteristicPath(serviceUuid, characteristicUuid)
        if (path == null) {
            logger.e { "couldn't find characteristic: $characteristicUuid" }
            return false
        }
        return try {
            val characteristic = connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java)
            val type = when (writeType) {
                GattWriteType.WithResponse -> "request"
                GattWriteType.NoResponse -> "command"
            }
            characteristic.WriteValue(value, mapOf("type" to Variant(type)))
            true
        } catch (e: Exception) {
            logger.v(e) { "error writing characteristic" }
            false
        }
    }

    override suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray? {
        val path = findCharacteristicPath(serviceUuid, characteristicUuid)
        if (path == null) {
            logger.e { "couldn't find characteristic: $characteristicUuid" }
            return null
        }
        return try {
            connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java).ReadValue(emptyMap())
        } catch (e: Exception) {
            logger.e(e) { "error reading characteristic" }
            null
        }
    }

    override val services: List<GattService>?
        get() = discoveredServices

    // BlueZ doesn't expose the negotiated ATT MTU without going through AcquireWrite/
    // AcquireNotify (not implemented here - StartNotify/WriteValue cover PPoG's needs). Falls
    // back to the un-negotiated default; real MTU negotiation is future work if throughput
    // becomes a problem in practice.
    override suspend fun requestMtu(mtu: Int): Int = mtu

    override suspend fun getMtu(): Int = LEConstants.DEFAULT_MTU

    override suspend fun refreshServicesNative(): Boolean = false

    override fun close() {
        // Connection lifecycle (including disconnect) is owned by DbusGattConnector.
    }
}

internal fun List<String>.asPropertiesBitmask(): Int {
    var bitmask = 0
    if ("broadcast" in this) bitmask = bitmask or 0x01
    if ("read" in this) bitmask = bitmask or 0x02
    if ("write-without-response" in this) bitmask = bitmask or 0x04
    if ("write" in this) bitmask = bitmask or 0x08
    if ("notify" in this) bitmask = bitmask or 0x10
    if ("indicate" in this) bitmask = bitmask or 0x20
    if ("authenticated-signed-writes" in this) bitmask = bitmask or 0x40
    if ("extended-properties" in this) bitmask = bitmask or 0x80
    return bitmask
}
