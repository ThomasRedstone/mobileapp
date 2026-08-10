package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.classic.PEBBLE_NAME_REGEX
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.parseBluezDevices
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.TransportType
import org.freedesktop.dbus.interfaces.ObjectManager

private val logger = Logger.withTag("BondedWatchSeeder")

internal actual suspend fun seedBondedWatches(
    appContext: AppContext,
    knownWatchDao: KnownWatchDao,
): List<KnownWatchItem>? {
    val devices = try {
        val connection = buildSystemBusConnection()
        try {
            connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
                .GetManagedObjects()
                .parseBluezDevices()
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        logger.w(e) { "Couldn't reach BlueZ; will retry next launch" }
        return null
    }

    val existing = knownWatchDao.knownWatches().map { it.transportIdentifier }.toHashSet()
    val inserted = mutableListOf<KnownWatchItem>()

    for (device in devices) {
        if (!device.bonded) continue
        val name = device.name ?: continue
        if (!PEBBLE_NAME_REGEX.matches(name)) continue
        val address = device.address?.uppercase() ?: continue
        if (address in existing) {
            logger.d { "Bonded Pebble $name ($address) already in DB; skipping" }
            continue
        }

        // LinuxBleScanner/KableGattClient only speak BLE - there's no classic transport
        // implementation on this platform.
        val item = KnownWatchItem(
            transportIdentifier = address,
            transportType = TransportType.BluetoothLe,
            name = name,
            runningFwVersion = UNKNOWN_WATCH_SERIAL_OR_VERSION,
            serial = UNKNOWN_WATCH_SERIAL_OR_VERSION,
            connectGoal = false,
        )
        try {
            knownWatchDao.insertOrUpdate(item)
            inserted += item
            logger.i { "Seeded bonded Pebble $name ($address)" }
        } catch (e: Exception) {
            logger.w(e) { "Failed to insert seeded bonded Pebble $name ($address)" }
        }
    }

    return inserted
}
