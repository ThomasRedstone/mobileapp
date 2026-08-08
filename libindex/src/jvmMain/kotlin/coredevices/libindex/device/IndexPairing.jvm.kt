package coredevices.libindex.device

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private val logger = Logger.withTag("IndexPairing")

// No BlueZ/D-Bus bonding implementation yet, see docs/ubuntu-touch-poc-plan.md.
actual suspend fun createBond(context: AppContext, identifier: IndexIdentifier): Boolean {
    logger.w { "createBond() not implemented on JVM" }
    return false
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: IndexIdentifier
): Flow<BluetoothDevicePairEvent> = emptyFlow()
