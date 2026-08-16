package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.CONNECTION_PARAMETERS_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.launch

class ConnectionParams(
    private val scope: ConnectionCoroutineScope,
    private val blePlatformConfig: BlePlatformConfig,
) {
    suspend fun subscribeAndConfigure(gattClient: ConnectedGattClient): Boolean {
        // TODO scope this
        val sub = gattClient.subscribeToCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC)
        if (sub == null) {
            Logger.i("error subscribing to connection params (not present on core watches yet)")
            return false
        }
        scope.launch {
            sub.collect {
                Logger.d("connection params changed: ${it.joinToString()}")
            }
        }
        // Second byte is is_remote_device_managing_connection_parameters - writing 1 tells the
        // watch's firmware to never request a param change itself again. Only worth it on
        // platforms that then actually manage priority in its place (see BlePlatformConfig
        // .phoneManagesConnectionParams doc).
        val value = if (blePlatformConfig.phoneManagesConnectionParams) byteArrayOf(0, 1) else byteArrayOf(0, 0)
        return gattClient.writeCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC, value, GattWriteType.WithResponse)
    }
}