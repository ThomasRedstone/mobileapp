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
        val connection = buildSystemBusConnection()
        try {
            val adapter = connection.getRemoteObject("org.bluez", ADAPTER_PATH, Adapter1::class.java)
            try {
                adapter.StartDiscovery()
            } catch (e: Exception) {
                logger.e(e) { "StartDiscovery failed, aborting scan" }
                return@flow
            }
            try {
                val objectManager = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                val seen = mutableSetOf<String>()
                while (true) {
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
        private const val ADAPTER_PATH = "/org/bluez/hci0"
        private val POLL_INTERVAL = 2.seconds
    }
}
