package io.rebble.libpebblecommon.connection.bt.ble.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.FAKE_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.META_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.SERVER_META_RESPONSE
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.buildSystemBusConnection
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Hosts the real GATT server directly over `dbus-java`, exporting the object paths BlueZ needs
 * (`org.bluez.GattManager1.RegisterApplication`) rather than shelling out to a subprocess -
 * exec of system binaries (a Python companion driving `dbus-python`, the previous approach) is
 * denied under Click confinement, same as `busctl` was. Service/characteristic layout is ported
 * byte-for-byte from `GattServer.android.kt`/`LEConstants.kt`/`PebbleBle.android.kt`.
 */
private val logger = Logger.withTag("GattServer")

private const val GATT_APP_PATH = "/io/rebble/pebble/ppog"
private const val ADAPTER_PATH = "/org/bluez/hci0"

@DBusInterfaceName("org.bluez.GattManager1")
internal interface GattManager1 : DBusInterface {
    fun RegisterApplication(application: DBusPath, options: Map<String, Variant<*>>)
    fun UnregisterApplication(application: DBusPath)
}

@DBusInterfaceName("org.bluez.GattService1")
internal interface GattService1 : DBusInterface

@DBusInterfaceName("org.bluez.GattCharacteristic1")
internal interface GattCharacteristic1Server : DBusInterface {
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}

// BlueZ passes the connecting device as an object path
// (/org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX) under the "device" key, not a MAC string - convert to
// the colon-separated address the rest of the app (PebbleBleIdentifier) expects.
private fun deviceAddressFromOptions(options: Map<String, Variant<*>>): String {
    val path = (options["device"]?.value as? DBusPath)?.path ?: return ""
    val marker = "dev_"
    val idx = path.indexOf(marker)
    if (idx == -1) return ""
    return path.substring(idx + marker.length).replace('_', ':')
}

internal class ExportedService(
    val path: String,
    private val uuid: String,
    private val characteristicPaths: List<String>,
) : GattService1, Properties {
    override fun getObjectPath() = path
    override fun <A> Get(interfaceName: String, property: String): A =
        throw UnsupportedOperationException()
    override fun <A> Set(interfaceName: String, property: String, value: A) =
        throw UnsupportedOperationException()
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
        "UUID" to Variant(uuid),
        "Primary" to Variant(true),
        "Characteristics" to Variant(characteristicPaths.map { DBusPath(it) }, "ao"),
    )
}

internal class ExportedCharacteristic(
    val path: String,
    val uuid: String,
    private val servicePath: String,
    private val flags: List<String>,
    initialValue: ByteArray = ByteArray(0),
) : GattCharacteristic1Server, Properties {
    @Volatile var value: ByteArray = initialValue
    var onReadRequested: (device: String) -> Unit = {}
    var onWrite: (device: String, value: ByteArray) -> Unit = { _, _ -> }

    override fun getObjectPath() = path

    // Synchronous, matching the proven prototype: our characteristics (meta response, fake
    // service) hold a static value set at construction/via a write, so there's no need for an
    // async round-trip before answering BlueZ - onReadRequested fires for observability/future
    // dynamic characteristics, but the reply is always whatever's already cached.
    override fun ReadValue(options: Map<String, Variant<*>>): ByteArray {
        onReadRequested(deviceAddressFromOptions(options))
        return value
    }

    override fun WriteValue(newValue: ByteArray, options: Map<String, Variant<*>>) {
        value = newValue
        onWrite(deviceAddressFromOptions(options), newValue)
    }

    // No device/options arg here - matches BlueZ's real StartNotify() signature; notifications
    // are addressed by writing directly to whichever device registerDevice() registered, not by
    // tracking subscribers here.
    override fun StartNotify() {
        logger.d { "notify subscribed: $uuid" }
    }

    override fun StopNotify() {
        logger.d { "notify unsubscribed: $uuid" }
    }

    override fun <A> Get(interfaceName: String, property: String): A =
        throw UnsupportedOperationException()
    override fun <A> Set(interfaceName: String, property: String, value: A) =
        throw UnsupportedOperationException()
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
        "Service" to Variant(DBusPath(servicePath)),
        "UUID" to Variant(uuid),
        "Flags" to Variant(flags, "as"),
        "Descriptors" to Variant(emptyList<DBusPath>(), "ao"),
    )
}

internal class ExportedApplication(
    private val path: String,
    private val entries: Map<String, Map<String, Map<String, Variant<*>>>>,
) : ObjectManager {
    override fun getObjectPath() = path
    override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> {
        val result = entries.mapKeys { (p, _) -> DBusPath(p) }
        logger.d { "GetManagedObjects() called, returning ${result.size} objects: ${result.keys}" }
        return result
    }
}

actual fun openGattServer(
    appContext: AppContext,
    bleConfigFlow: BleConfigFlow,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
): GattServer? = GattServer(libPebbleCoroutineScope)

actual class GattServer(
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) {
    private val registeredDevices = ConcurrentHashMap<String, SendChannel<ByteArray>>()
    private val characteristicsByUuid = ConcurrentHashMap<String, ExportedCharacteristic>()
    private var servicesAdded = CompletableDeferred<Unit>()
    private var connection: DBusConnection? = null

    private val _characteristicReadRequest = MutableSharedFlow<ServerCharacteristicReadRequest>(
        extraBufferCapacity = 16,
    )
    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> = _characteristicReadRequest

    actual fun initServer() {
        libPebbleCoroutineScope.launch {
            try {
                val conn = buildSystemBusConnection()
                connection = conn

                val servicePath = "$GATT_APP_PATH/service0"
                val fakeServicePath = "$GATT_APP_PATH/service1"
                val metaCharPath = "$servicePath/char0"
                val ppogCharPath = "$servicePath/char1"
                val fakeCharPath = "$fakeServicePath/char0"

                val metaChar = ExportedCharacteristic(
                    metaCharPath, META_CHARACTERISTIC_SERVER.toString(), servicePath,
                    listOf("encrypt-read"), SERVER_META_RESPONSE,
                )
                metaChar.onReadRequested = { device ->
                    _characteristicReadRequest.tryEmit(
                        ServerCharacteristicReadRequest(PebbleBleIdentifier(device), META_CHARACTERISTIC_SERVER) {
                            metaChar.value = it
                            true
                        }
                    )
                }

                val ppogChar = ExportedCharacteristic(
                    ppogCharPath, PPOGATT_DEVICE_CHARACTERISTIC_SERVER.toString(), servicePath,
                    listOf("write-without-response", "notify"),
                )
                ppogChar.onWrite = { device, data ->
                    val channel = registeredDevices[device]
                    if (channel == null) {
                        logger.e { "write from unregistered device: $device" }
                    } else if (channel.trySend(data).isFailure) {
                        logger.e { "error writing to channel for $device" }
                    }
                }

                val fakeChar = ExportedCharacteristic(
                    fakeCharPath, FAKE_SERVICE_UUID.toString(), fakeServicePath, listOf("encrypt-read"),
                )
                fakeChar.onReadRequested = { device ->
                    _characteristicReadRequest.tryEmit(
                        ServerCharacteristicReadRequest(PebbleBleIdentifier(device), FAKE_SERVICE_UUID) {
                            fakeChar.value = it
                            true
                        }
                    )
                }

                characteristicsByUuid[metaChar.uuid] = metaChar
                characteristicsByUuid[ppogChar.uuid] = ppogChar
                characteristicsByUuid[fakeChar.uuid] = fakeChar

                val service = ExportedService(
                    servicePath, PPOGATT_DEVICE_SERVICE_UUID_SERVER.toString(),
                    listOf(metaCharPath, ppogCharPath),
                )
                val fakeService = ExportedService(
                    fakeServicePath, FAKE_SERVICE_UUID.toString(), listOf(fakeCharPath),
                )

                // Matches BlueZ's own test/example-gatt-server object structure exactly
                // (including the easy-to-miss empty "Descriptors" key on characteristics - omitting
                // it fails RegisterApplication with "No valid service object found").
                val entries = mapOf(
                    servicePath to mapOf("org.bluez.GattService1" to service.GetAll("")),
                    metaCharPath to mapOf("org.bluez.GattCharacteristic1" to metaChar.GetAll("")),
                    ppogCharPath to mapOf("org.bluez.GattCharacteristic1" to ppogChar.GetAll("")),
                    fakeServicePath to mapOf("org.bluez.GattService1" to fakeService.GetAll("")),
                    fakeCharPath to mapOf("org.bluez.GattCharacteristic1" to fakeChar.GetAll("")),
                )
                val application = ExportedApplication("/", entries)

                conn.exportObject("/", application)
                conn.exportObject(servicePath, service)
                conn.exportObject(fakeServicePath, fakeService)
                conn.exportObject(metaCharPath, metaChar)
                conn.exportObject(ppogCharPath, ppogChar)
                conn.exportObject(fakeCharPath, fakeChar)

                val gattManager = conn.getRemoteObject(
                    "org.bluez", ADAPTER_PATH, GattManager1::class.java,
                )
                try {
                    gattManager.RegisterApplication(DBusPath("/"), emptyMap())
                    servicesAdded.complete(Unit)
                } catch (e: Exception) {
                    logger.e(e) { "RegisterApplication failed" }
                }
                logger.d("gatt server ready")
            } catch (e: Exception) {
                logger.e(e) { "error initializing gatt server" }
            }
        }
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
        val conn = connection ?: return
        try {
            conn.getRemoteObject("org.bluez", ADAPTER_PATH, GattManager1::class.java)
                .UnregisterApplication(DBusPath("/"))
        } catch (e: Exception) {
            // Best-effort, matches the previous companion process's own DBusException swallow.
        }
        conn.disconnect()
        connection = null
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
        val conn = connection ?: return SendResult.Failed
        val char = characteristicsByUuid[characteristicUuid.toString()] ?: run {
            logger.e { "sendData: unknown characteristic: $characteristicUuid" }
            return SendResult.Failed
        }
        // Best-effort: unlike Android's notifyCharacteristicChanged, BlueZ's
        // PropertiesChanged-based notify has no per-send completion callback
        // reaching us here, so this can't distinguish "sent" from "actually
        // delivered" the way the Android GattServer's writing/timeout tracking
        // does.
        return try {
            char.value = data
            conn.sendMessage(
                Properties.PropertiesChanged(char.path, "org.bluez.GattCharacteristic1", mapOf("Value" to Variant(data)), emptyList())
            )
            SendResult.Success
        } catch (e: Exception) {
            logger.e(e) { "error sending notify" }
            SendResult.Failed
        }
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean = false
}
