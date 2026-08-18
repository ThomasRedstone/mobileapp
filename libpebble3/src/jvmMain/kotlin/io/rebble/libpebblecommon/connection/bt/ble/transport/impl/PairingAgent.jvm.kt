package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32

private val logger = Logger.withTag("PairingAgent")

// Reuses the existing GATT-server AppArmor grant (coreapp-apparmor-patch's
// "/{,io/rebble/pebble/ppog/**}" receive rule) instead of needing a new supplementary rule of its
// own - bluetoothd calling into an object exported outside that tree is denied at the kernel
// level, same failure shape documented for the GATT server itself.
internal const val PAIRING_AGENT_PATH = "/io/rebble/pebble/ppog/pairing_agent"

// DisplayYesNo, not NoInputNoOutput - the watch has its own screen and already shows a real
// confirmation prompt, so this negotiates Numeric Comparison rather than downgrading every
// pairing to the weaker Just Works model.
internal const val PAIRING_AGENT_CAPABILITY = "DisplayYesNo"

@DBusInterfaceName("org.bluez.AgentManager1")
internal interface AgentManager1 : DBusInterface {
    fun RegisterAgent(agent: DBusPath, capability: String)
    fun UnregisterAgent(agent: DBusPath)
    fun RequestDefaultAgent(agent: DBusPath)
}

@DBusInterfaceName("org.bluez.Agent1")
internal interface Agent1 : DBusInterface {
    fun Release()
    fun RequestPinCode(device: DBusPath): String
    fun DisplayPinCode(device: DBusPath, pincode: String)
    fun RequestPasskey(device: DBusPath): UInt32
    fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16)
    fun RequestConfirmation(device: DBusPath, passkey: UInt32)
    fun RequestAuthorization(device: DBusPath)
    fun AuthorizeService(device: DBusPath, uuid: String)
    fun Cancel()
}

/**
 * A minimal `org.bluez.Agent1` that auto-accepts pairing for exactly [targetDevicePath] - meant
 * to be registered as the system default agent only for the duration of a single `createBond()`
 * call (see [io.rebble.libpebblecommon.connection.bt.Pairing.jvm.kt]), then unregistered.
 *
 * The watch's own on-screen pairing prompt is the actual human-in-the-loop check here; this
 * agent exists only because nothing on this platform reliably answers the *phone's* side of the
 * confirmation - `indicator-bluetooth`'s own registered agent was confirmed live to silently
 * never answer `RequestConfirmation`, so BlueZ's ~30s default agent-reply timeout fires every
 * time and `Device1.Pair()` fails with `AuthenticationCanceled`. Any device other than the one
 * currently being paired is rejected outright rather than silently accepted, since this
 * temporarily becomes the *system* default agent, not just an agent for our own device.
 */
internal class PairingAgent(private val targetDevicePath: String) : Agent1 {
    override fun getObjectPath() = PAIRING_AGENT_PATH

    override fun Release() {}

    override fun RequestPinCode(device: DBusPath): String = reject(device)

    override fun DisplayPinCode(device: DBusPath, pincode: String) {}

    override fun RequestPasskey(device: DBusPath): UInt32 = reject(device)

    override fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16) {}

    override fun RequestConfirmation(device: DBusPath, passkey: UInt32) {
        requireTarget(device)
        logger.d { "auto-confirming pairing for $targetDevicePath" }
    }

    override fun RequestAuthorization(device: DBusPath) {
        requireTarget(device)
        logger.d { "auto-authorizing pairing for $targetDevicePath" }
    }

    override fun AuthorizeService(device: DBusPath, uuid: String) {
        requireTarget(device)
    }

    override fun Cancel() {
        logger.w { "pairing agent request canceled by bluetoothd" }
    }

    private fun requireTarget(device: DBusPath) {
        if (device.path != targetDevicePath) reject(device)
    }

    private fun reject(device: DBusPath): Nothing {
        logger.w { "rejecting agent request for $device - only pairing $targetDevicePath" }
        throw UnsupportedOperationException("not the device currently being paired")
    }
}
