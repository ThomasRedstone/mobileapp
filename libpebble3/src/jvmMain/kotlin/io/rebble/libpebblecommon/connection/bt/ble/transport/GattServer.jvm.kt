package io.rebble.libpebblecommon.connection.bt.ble.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Hosts the real GATT server via a persistent Python companion process
 * (`gatt_server_companion.py`, bundled as a jvmMain resource) speaking
 * dbus-python to BlueZ, driven over stdin/stdout line-delimited JSON.
 *
 * Not `dbus-java`: its SASL EXTERNAL auth sends the wrong UID against this
 * platform's BlueZ/dbus-daemon (traced at the syscall level — a genuine
 * upstream bug). Not `busctl`: it can only invoke methods on other
 * services, not export/host object paths of our own, which a GATT server
 * needs. dbus-python + a GLib mainloop is the one binding proven reliable
 * here during the Ubuntu Touch PoC (docs/ubuntu-touch-poc-plan.md).
 */
private val logger = Logger.withTag("GattServer")

actual fun openGattServer(
    appContext: AppContext,
    bleConfigFlow: BleConfigFlow,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
): GattServer? {
    return try {
        val scriptFile = extractCompanionScript()
        val process = ProcessBuilder("python3", scriptFile.absolutePath)
            .redirectErrorStream(false)
            .start()
        GattServer(process, libPebbleCoroutineScope)
    } catch (e: Exception) {
        logger.e(e) { "error starting gatt server companion process" }
        null
    }
}

private fun extractCompanionScript(): File {
    val resource = object {}.javaClass.getResourceAsStream("/gatt_server_companion.py")
        ?: error("gatt_server_companion.py missing from jvmMain resources")
    val file = File.createTempFile("gatt_server_companion", ".py")
    file.deleteOnExit()
    resource.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
    return file
}

actual class GattServer(
    private val process: Process,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = process.outputStream.bufferedWriter()
    private val registeredDevices = ConcurrentHashMap<String, SendChannel<ByteArray>>()
    private var servicesAdded = CompletableDeferred<Unit>()

    private val _characteristicReadRequest = MutableSharedFlow<ServerCharacteristicReadRequest>(
        extraBufferCapacity = 16,
    )
    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> = _characteristicReadRequest

    actual fun initServer() {
        libPebbleCoroutineScope.launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().lineSequence().forEach { line ->
                handleEvent(line)
            }
        }
    }

    private fun handleEvent(line: String) {
        val event = try {
            json.parseToJsonElement(line).let { it as? JsonObject } ?: return
        } catch (e: Exception) {
            logger.e(e) { "malformed companion event: $line" }
            return
        }
        when (event["event"]?.jsonPrimitive?.content) {
            "ready" -> logger.d("gatt server companion ready")
            "services_added" -> servicesAdded.complete(Unit)
            "read_request" -> {
                val uuid = event["uuid"]?.jsonPrimitive?.content ?: return
                val device = event["device"]?.jsonPrimitive?.content.orEmpty()
                _characteristicReadRequest.tryEmit(
                    ServerCharacteristicReadRequest(
                        deviceId = PebbleBleIdentifier(device),
                        uuid = Uuid.parse(uuid),
                        respond = { bytes -> setValue(uuid, bytes); true },
                    )
                )
            }
            "write" -> {
                val device = event["device"]?.jsonPrimitive?.content.orEmpty()
                val dataHex = event["data_hex"]?.jsonPrimitive?.content ?: return
                val channel = registeredDevices[device]
                if (channel == null) {
                    logger.e { "write from unregistered device: $device" }
                    return
                }
                val result = channel.trySend(dataHex.hexToByteArray())
                if (result.isFailure) {
                    logger.e { "error writing to channel: $result" }
                }
            }
            "notify_subscribed" -> logger.d("notify subscribed: ${event["uuid"]}")
            "error" -> logger.e { "companion error: ${event["message"]}" }
        }
    }

    private fun setValue(uuid: String, data: ByteArray) {
        sendCommand(buildJsonCommand("set_value", uuid, data))
    }

    actual suspend fun addServices() {
        withTimeoutOrNull(10.seconds) { servicesAdded.await() }
            ?: logger.w { "timed out waiting for gatt services to register" }
    }

    actual suspend fun removeServices() {
        // Not wired up: the Android implementation avoids calling this too,
        // real testing found removing/re-adding services broke connectivity.
    }

    actual suspend fun closeServer() {
        sendCommand("""{"cmd":"close"}""")
        process.destroy()
    }

    actual fun registerDevice(identifier: PebbleBleIdentifier, sendChannel: SendChannel<ByteArray>) {
        registeredDevices[identifier.asString] = sendChannel
    }

    actual fun unregisterDevice(identifier: PebbleBleIdentifier) {
        registeredDevices.remove(identifier.asString)
    }

    actual suspend fun sendData(
        identifier: PebbleBleIdentifier,
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        data: ByteArray,
    ): SendResult {
        if (!registeredDevices.containsKey(identifier.asString)) {
            logger.e { "sendData: couldn't find registered device: $identifier" }
            return SendResult.Failed
        }
        // Best-effort: unlike Android's notifyCharacteristicChanged, BlueZ's
        // PropertiesChanged-based notify has no per-send completion callback
        // reaching us here, so this can't distinguish "sent" from "actually
        // delivered" the way the Android GattServer's writing/timeout tracking
        // does.
        return if (sendCommand(buildJsonCommand("notify", characteristicUuid.toString(), data))) {
            SendResult.Success
        } else {
            SendResult.Failed
        }
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean = false

    private fun sendCommand(commandJson: String): Boolean {
        return try {
            synchronized(writer) {
                writer.write(commandJson)
                writer.newLine()
                writer.flush()
            }
            true
        } catch (e: Exception) {
            logger.e(e) { "error writing command to gatt server companion" }
            false
        }
    }

    private fun buildJsonCommand(cmd: String, uuid: String, data: ByteArray): String {
        val dataHex = data.joinToString("") { "%02x".format(it) }
        return """{"cmd":"$cmd","uuid":"$uuid","data_hex":"$dataHex"}"""
    }

    private fun String.hexToByteArray(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
