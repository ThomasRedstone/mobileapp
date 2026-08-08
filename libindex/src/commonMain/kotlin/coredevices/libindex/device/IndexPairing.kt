package coredevices.libindex.device

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.flow.Flow

interface IndexPairing {
    suspend fun pairDevice(device: DiscoveredIndexDevice): IndexPairingResult
}

class BluetoothDevicePairEvent(val device: IndexIdentifier, val bondState: Int, val unbondReason: Int?)

expect suspend fun createBond(context: AppContext, identifier: IndexIdentifier): Boolean

expect fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: IndexIdentifier
): Flow<BluetoothDevicePairEvent>

sealed interface PairingRequestResult {
    object UserRejected: PairingRequestResult
    class Error(val cause: Throwable): PairingRequestResult
    object CreateBondFailed: PairingRequestResult
    object RingAlreadyPaired: PairingRequestResult
    object Paired: PairingRequestResult
}

sealed interface IndexPairingResult {
    object Success: IndexPairingResult
    class PairingFailure(val cause: PairingRequestResult): IndexPairingResult
    class EraseFailed(val cause: Throwable): IndexPairingResult
}
