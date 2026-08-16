package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.Adapter1
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.resolveAdapterPath
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.NotConnected
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties

private const val POLL_INTERVAL_MS = 3_000L

// closeGattServerWhenBtDisabled defaults to true on this platform, so a single transient poll
// failure reported as Disabled tears down the GATT server on a real connection — require two
// consecutive failures before treating the adapter as actually down.
private const val CONSECUTIVE_FAILURES_BEFORE_DISABLED = 2

private val logger = Logger.withTag("BluetoothState")

private fun subscribeAdapterChanged(connection: DBusConnection, adapterPath: String, onChanged: () -> Unit) {
    runCatching {
        connection.addSigHandler(
            Properties.PropertiesChanged::class.java,
            connection.getRemoteObject("org.bluez", adapterPath, Adapter1::class.java),
            DBusSigHandler<Properties.PropertiesChanged> { signal ->
                if (signal.interfaceName != "org.bluez.Adapter1") return@DBusSigHandler
                if ("Powered" in signal.propertiesChanged) onChanged()
            },
        )
    }.onFailure { logger.w(it) { "couldn't subscribe to adapter PropertiesChanged - relying on poll only" } }
}

// Subscribes to the adapter's own PropertiesChanged for immediate detection (scoped to the
// adapter's object, so this doesn't pay the same AppArmor-mediation-noise cost an unscoped
// match rule would), with the original 3s poll kept running underneath as a backstop: a dead
// connection just stops delivering signals silently, with no exception to catch, so periodic
// polling is still the only way to notice that case and rebuild.
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? = callbackFlow {
    var connection = buildSystemBusConnection()
    var adapterPath = resolveAdapterPath(connection)
    var props = connection.getRemoteObject("org.bluez", adapterPath, Properties::class.java)
    var last: BluetoothState? = null
    var consecutiveFailures = 0
    lateinit var resubscribe: () -> Unit
    // The adapter PropertiesChanged handler below fires on dbus-java's own signal-dispatch
    // thread, while the poll loop runs on this coroutine's dispatcher - both call checkAndEmit(),
    // which mutates connection/props/adapterPath/last/consecutiveFailures. Unsynchronized, that's
    // a stale read or a doubled connection-rebuild race, not just a theoretical concern once both
    // a live signal and a poll tick can land close together.
    val stateLock = Any()

    fun currentState(): BluetoothState? {
        return try {
            val powered = props.Get<Boolean>("org.bluez.Adapter1", "Powered") == true
            consecutiveFailures = 0
            if (powered) BluetoothState.Enabled else BluetoothState.Disabled
        } catch (e: Exception) {
            consecutiveFailures++
            logger.w(e) { "Couldn't poll adapter Powered state (failure $consecutiveFailures)" }
            // A NotConnected connection never recovers on its own — rebuild it (and its signal
            // subscription, which a fresh connection starts with none of), or every subsequent
            // poll fails forever.
            if (e is NotConnected) {
                runCatching { connection.disconnect() }
                connection = buildSystemBusConnection()
                adapterPath = resolveAdapterPath(connection)
                props = connection.getRemoteObject("org.bluez", adapterPath, Properties::class.java)
                resubscribe()
            }
            if (consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_DISABLED) last else BluetoothState.Disabled
        }
    }

    fun checkAndEmit() {
        synchronized(stateLock) {
            val state = currentState()
            if (state != null && state != last) {
                last = state
                trySend(state)
            }
        }
    }

    resubscribe = { subscribeAdapterChanged(connection, adapterPath) { checkAndEmit() } }
    resubscribe()

    val pollJob = launch {
        while (true) {
            delay(POLL_INTERVAL_MS)
            checkAndEmit()
        }
    }
    awaitClose {
        pollJob.cancel()
        connection.disconnect()
    }
}
