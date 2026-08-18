package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.Adapter1
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.AgentManager1
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.Device1
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.PAIRING_AGENT_CAPABILITY
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.PAIRING_AGENT_PATH
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.PairingAgent
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.SelfHealingSystemBusConnection
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.devicePathFor
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.resolveAdapterPath
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * BlueZ over `dbus-java` for the BLE bond lifecycle. Real, proven flow on real hardware:
 * `Device1.Pair()` produces pairing prompts on both devices, and `Device1.Paired` flips true
 * once accepted (docs/ubuntu-touch-poc-plan.md).
 */
private val logger = Logger.withTag("Pairing")
private val POLL_INTERVAL = 1.seconds
private const val REDISCOVER_TIMEOUT_MS = 15_000L
private const val REDISCOVER_POLL_MS = 500L

// Reused across calls rather than opened per poll - SASL handshake overhead per connection is
// real, and DbusConnectedGattClient already establishes the same "one connection, many calls"
// pattern for the GATT side. Self-healing because this connection lives for the process lifetime
// and a dead D-Bus connection never recovers on its own (confirmed live: every Pair() after one
// NotConnected failed forever). The reply-wait timeout generous enough for Device1.Pair() (BlueZ
// blocks until the watch's own pairing prompt is answered by a human) is set globally in
// buildSystemBusConnection() itself - see BluezDbus.jvm.kt.
private val connectionHolder = SelfHealingSystemBusConnection()

// The adapter object doesn't move around at runtime, so resolving it once and caching is enough
// - avoids an extra ObjectManager round-trip on every single poll/pair call.
@Volatile
private var cachedAdapterPath: String? = null

private fun adapterPath(): String =
    cachedAdapterPath ?: connectionHolder.withConnection { resolveAdapterPath(it) }.also { cachedAdapterPath = it }

private fun devicePath(identifier: PebbleBleIdentifier): String =
    devicePathFor(adapterPath(), identifier.asString)

private fun isPaired(devicePath: String): Boolean = try {
    connectionHolder.withConnection { connection ->
        val props = connection.getRemoteObject("org.bluez", devicePath, Properties::class.java)
        props.Get<Boolean>("org.bluez.Device1", "Paired") == true
    }
} catch (e: Exception) {
    logger.e(e) { "couldn't read Paired for $devicePath" }
    false
}

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean = isPaired(devicePath(identifier))

// After RemoveDevice(), BlueZ deletes the D-Bus object entirely - Pair() needs it to exist
// again first. Confirmed live this session: an Obelix watch (DF:07:0A:D4:70:B8) reappeared via
// StartDiscovery within a few seconds of being removed, for the just-unbonded case specifically
// (this doesn't contradict "Obelix doesn't advertise to general discovery when disconnected" -
// that's about an already-disconnected-but-still-bonded device, a different state).
private fun waitForDeviceObject(connection: DBusConnection, path: String): Boolean {
    val deadline = System.currentTimeMillis() + REDISCOVER_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        try {
            val props = connection.getRemoteObject("org.bluez", path, Properties::class.java)
            if (props.Get<Any>("org.bluez.Device1", "Address") != null) return true
        } catch (e: Exception) {
            // Not there yet.
        }
        Thread.sleep(REDISCOVER_POLL_MS)
    }
    return false
}

// indicator-bluetooth's own registered agent was confirmed live to never actually answer
// RequestConfirmation - BlueZ's ~30s default agent-reply timeout fires every time and Pair()
// fails with AuthenticationCanceled. Registering our own agent as the system default only for
// the duration of this one Pair() call unblocks pairing without depending on that agent - it's
// unregistered again in the finally block regardless of outcome, handing default-agent status
// back to whatever else is registered (indicator-bluetooth) for every other device on the phone.
private fun withPairingAgent(connection: DBusConnection, devicePath: String, block: () -> Unit) {
    val agentManager = connection.getRemoteObject("org.bluez", "/org/bluez", AgentManager1::class.java)
    val agentPath = DBusPath(PAIRING_AGENT_PATH)
    connection.exportObject(PAIRING_AGENT_PATH, PairingAgent(devicePath))
    try {
        agentManager.RegisterAgent(agentPath, PAIRING_AGENT_CAPABILITY)
        agentManager.RequestDefaultAgent(agentPath)
        block()
    } finally {
        runCatching { agentManager.UnregisterAgent(agentPath) }
            .onFailure { logger.w(it) { "UnregisterAgent failed" } }
        connection.unExportObject(PAIRING_AGENT_PATH)
    }
}

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    logger.d("createBond()")
    val path = devicePath(identifier)
    return try {
        // Pair() blocks on its D-Bus reply until the human answers the watch's own pairing
        // prompt - firing everything on a background thread keeps createBond() itself
        // non-blocking, matching every other platform (this only requests the bond;
        // PebblePairing.kt already polls Paired separately with its own PENDING_BOND_TIMEOUT).
        Thread({
            try {
                // Device1.Pair() on an already-Paired BlueZ device throws AlreadyExists and does
                // nothing - exactly the case that gets us called in the first place (the watch
                // reports paired but the link won't encrypt). Force a fresh pairing instead of
                // silently no-op'ing.
                if (isPaired(path)) {
                    logger.d { "already marked Paired - removing device and rescanning to force a fresh pairing" }
                    connectionHolder.withConnection { connection ->
                        val adapter = connection.getRemoteObject("org.bluez", adapterPath(), Adapter1::class.java)
                        runCatching { adapter.RemoveDevice(DBusPath(path)) }
                        runCatching { adapter.StartDiscovery() }
                        val found = waitForDeviceObject(connection, path)
                        runCatching { adapter.StopDiscovery() }
                        if (!found) {
                            logger.w { "device object didn't reappear after RemoveDevice - Pair() will likely fail" }
                        }
                    }
                }
                connectionHolder.withConnection { connection ->
                    val device = connection.getRemoteObject("org.bluez", path, Device1::class.java)
                    withPairingAgent(connection, path) { device.Pair() }
                }
            } catch (e: Exception) {
                logger.e(e) { "Pair() failed" }
            }
        }, "dbus-pair-${identifier.asString}").apply { isDaemon = true }.start()
        true
    } catch (e: Exception) {
        logger.e(e) { "createBond() failed to start" }
        false
    }
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivityWatcher: ConnectivityWatcher,
    connectionScope: ConnectionCoroutineScope,
): Flow<BluetoothDevicePairEvent> = flow {
    val path = devicePath(identifier)
    var lastPaired: Boolean? = null
    // Polls the Paired property rather than subscribing to PropertiesChanged - adequate for the
    // bond handshake, which isn't latency-sensitive.
    while (true) {
        val paired = isPaired(path)
        if (paired != lastPaired) {
            lastPaired = paired
            emit(
                BluetoothDevicePairEvent(
                    device = identifier,
                    bondState = if (paired) BOND_BONDED else BOND_NONE,
                    unbondReason = null,
                )
            )
        }
        delay(POLL_INTERVAL)
    }
}

// BT Classic isn't supported on this platform (BlePlatformConfig.supportsBtClassic = false).
actual fun isBondedClassic(identifier: PebbleBtClassicIdentifier): Boolean = false

actual fun createBondClassic(identifier: PebbleBtClassicIdentifier): Boolean = false

actual fun getBluetoothClassicDevicePairEvents(
    context: AppContext,
    identifier: PebbleBtClassicIdentifier,
): Flow<BluetoothClassicDevicePairEvent> = flow { }
