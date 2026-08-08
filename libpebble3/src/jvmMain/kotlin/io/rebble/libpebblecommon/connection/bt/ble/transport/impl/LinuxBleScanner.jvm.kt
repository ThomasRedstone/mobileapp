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
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("LinuxBleScanner")

/**
 * BleScanner for Linux/Ubuntu Touch, driving BlueZ over `busctl` (see
 * [BusctlDbus] for why not a real D-Bus binding). Kable has no Linux
 * backend, so unlike the other platforms this isn't a [KableBleScanner] —
 * it talks to BlueZ directly and only produces the same [BleScanResult]
 * shape.
 */
internal class LinuxBleScanner(
    @Suppress("UNUSED_PARAMETER") private val bleConfigFlow: BleConfigFlow,
) : BleScanner {
    override fun scan(): Flow<BleScanResult> = flow {
        val seen = mutableSetOf<String>()
        val started = BusctlDbus.call(
            "org.bluez", ADAPTER_PATH, "org.bluez.Adapter1", "StartDiscovery"
        )
        if (started == null) {
            logger.e { "StartDiscovery failed, aborting scan" }
            return@flow
        }
        try {
            while (true) {
                val managedObjects = BusctlDbus.getManagedObjects()
                if (managedObjects != null) {
                    BluezObjectParser.parse(managedObjects).forEach { device ->
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
                }
                delay(POLL_INTERVAL)
            }
        } finally {
            BusctlDbus.call("org.bluez", ADAPTER_PATH, "org.bluez.Adapter1", "StopDiscovery")
        }
    }

    companion object {
        private const val ADAPTER_PATH = "/org/bluez/hci0"
        private val POLL_INTERVAL = 2.seconds
    }
}
