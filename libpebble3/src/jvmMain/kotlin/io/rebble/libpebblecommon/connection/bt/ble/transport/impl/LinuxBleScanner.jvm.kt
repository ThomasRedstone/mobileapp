package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.ManufacturerData
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.freedesktop.dbus.exceptions.NotConnected
import org.freedesktop.dbus.interfaces.ObjectManager
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("LinuxBleScanner")

/**
 * BleScanner for Linux/Ubuntu Touch, driving BlueZ directly over D-Bus (`dbus-java`). Kable has
 * no Linux backend, so unlike the other platforms this isn't a [KableBleScanner] — it talks to
 * BlueZ directly and only produces the same [BleScanResult] shape.
 */
internal class LinuxBleScanner(
    @Suppress("UNUSED_PARAMETER") private val bleConfigFlow: BleConfigFlow,
) : BleScanner {
    override fun scan(): Flow<BleScanResult> = flow {
        var connection = buildSystemBusConnection()
        try {
            var adapter = connection.getRemoteObject("org.bluez", resolveAdapterPath(connection), Adapter1::class.java)
            try {
                adapter.StartDiscovery()
            } catch (e: Exception) {
                if (e.message?.contains("InProgress") == true) {
                    // BlueZ is already discovering (another concurrent scan, or a leftover from
                    // one that didn't fully stop) - the outcome we actually want (an active
                    // discovery) already holds, so proceed rather than abort the whole flow.
                    logger.d { "StartDiscovery: already discovering, continuing" }
                } else {
                    logger.e(e) { "StartDiscovery failed, aborting scan" }
                    return@flow
                }
            }
            try {
                var objectManager = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                val seen = mutableSetOf<String>()
                while (true) {
                    try {
                        objectManager.GetManagedObjects().parseBluezDevices().forEach { device ->
                            val address = device.address ?: return@forEach
                            if (!seen.add(address)) return@forEach
                            val manufacturerData = device.manufacturerData.entries.firstOrNull() ?: return@forEach
                            emit(
                                BleScanResult(
                                    identifier = PebbleBleIdentifier(address),
                                    name = device.name ?: address,
                                    rssi = device.rssi ?: 0,
                                    manufacturerData = ManufacturerData(manufacturerData.key, manufacturerData.value),
                                )
                            )
                        }
                    } catch (e: NotConnected) {
                        // A dead connection never recovers on its own — rebuild it and re-arm
                        // discovery, or every subsequent poll fails forever.
                        logger.w(e) { "Scan connection died, rebuilding" }
                        runCatching { connection.disconnect() }
                        connection = buildSystemBusConnection()
                        adapter = connection.getRemoteObject("org.bluez", resolveAdapterPath(connection), Adapter1::class.java)
                        objectManager = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                        runCatching { adapter.StartDiscovery() }
                    }
                    delay(POLL_INTERVAL)
                }
            } finally {
                runCatching { adapter.StopDiscovery() }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val POLL_INTERVAL = 2.seconds
    }
}
