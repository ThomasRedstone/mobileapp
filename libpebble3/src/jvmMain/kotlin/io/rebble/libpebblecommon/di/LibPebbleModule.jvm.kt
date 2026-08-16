package io.rebble.libpebblecommon.di

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.DbusGattConnector
import io.rebble.libpebblecommon.connection.bt.classic.transport.ClassicScanner
import io.rebble.libpebblecommon.connection.bt.classic.transport.JvmClassicScanner
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.util.SystemGeolocation
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File
import java.util.Properties

/**
 * Talks to BlueZ directly over D-Bus (DbusGattClient.jvm.kt) rather than through Kable: its
 * `kable-btleplug-ffi` JVM/JNI bridge never actually issues a D-Bus call in this sandboxed
 * environment (docs/ubuntu-touch-poc-plan.md, "Kable/btleplug never actually attempts the
 * connection"). Constructed directly rather than via a `scopedOf` registration since
 * `DbusGattConnector` only exists on this platform's `ConnectionScope`.
 */
actual fun Scope.createBleGattConnector(): GattConnector = DbusGattConnector(
    identifier = get<PebbleBleIdentifier>(),
    scope = get(),
    blePlatformConfig = get(),
)

// Settings()'s JVM no-arg factory is PreferencesSettings(Preferences.userRoot()), whose backing
// store resolves under $HOME/.java/.userPrefs/ - outside the Click's writable dirs under real
// confinement, same bug class as JSLocalStorageInterface.jvm.kt (:libpebble3 deliberately avoids
// depending on :util's AppDirs, being mirrored from a standalone repo, hence $TMPDIR here too).
actual fun createLibPebbleSettings(): Settings {
    val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    val file = File(tmpDir, "libpebble-settings.properties")
    val properties = Properties()
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return PropertiesSettings(properties) { toSave ->
        file.parentFile?.mkdirs()
        file.outputStream().use { toSave.store(it, null) }
    }
}

/**
 * platformModule for Linux/Ubuntu Touch. The BLE connection path is real (see
 * LinuxBleScanner/GattServer.jvm.kt/Pairing.jvm.kt), as is missed-call and SMS forwarding via
 * com.lomiri.HistoryService (LinuxSystemCallLog/LinuxNotificationListenerConnection - see
 * docs/ubuntu-touch-notification-bridge-plan.md). The remaining phone-integration features
 * (calendar, contacts, music, geolocation, live call state) have no meaningful equivalent on a
 * desktop JVM target and stay bound to the no-op implementations in LinuxPlatformServices.kt.
 */
actual val platformModule: Module = module {
    // Shared by LinuxSystemCallLog and LinuxNotificationListenerConnection - each independently
    // building its own session-bus connection hit a real dbus-java race (NullPointerException
    // from AddMatch(), confirmed live), a single eagerly-built connection doesn't.
    single { buildSessionBusConnection() }
    single {
        PhoneCapabilities(CommonPhoneCapabilities)
    }
    single {
        PlatformFlags(
            PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Linux, emptyList())
        )
    }
    singleOf(::LinuxNotificationListenerConnection) bind NotificationListenerConnection::class
    singleOf(::LinuxNotificationActionHandler) bind PlatformNotificationActionHandler::class
    singleOf(::LinuxNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::LinuxSystemCalendar) bind SystemCalendar::class
    singleOf(::LinuxCalendarActionHandler) bind PlatformCalendarActionHandler::class
    singleOf(::LinuxSystemCallLog) bind SystemCallLog::class
    singleOf(::LinuxSystemMusicControl) bind SystemMusicControl::class
    singleOf(::LinuxSystemGeolocation) bind SystemGeolocation::class
    singleOf(::LinuxOtherPebbleApps) bind OtherPebbleApps::class
    singleOf(::LinuxSystemContacts) bind SystemContacts::class
    singleOf(::LinuxLegacyPhoneReceiver) bind LegacyPhoneReceiver::class
    single { PlatformConfig(syncNotificationApps = false) }
    single {
        BlePlatformConfig(
            supportsBtClassic = false,
            delayBleConnectionsAfterAppStart = true,
            // busctl/dbus-python control BlueZ directly rather than through an OS
            // BLE stack with its own autoConnect/GATT-cache semantics.
            supportsGattAutoConnect = false,
            // requestMtu()/getMtu() don't do real negotiation here (BlueZ only exposes the
            // negotiated MTU via AcquireWrite/AcquireNotify, not implemented yet) - with this
            // true, Mtu.update() drove the MTU StateFlow through 23 -> 339 -> 23 every connect
            // (requestMtu() echoing the request, immediately overwritten by getMtu()'s hardcoded
            // floor), and PPoG.updateMtu() throws on any decrease, causing an intermittent,
            // timing-dependent connection failure whenever the transient 339 was observed.
            useNativeMtu = false,
            // Nothing on this platform calls the equivalent of requestConnectionPriority, so
            // telling the watch's firmware to stop managing its own connection parameters (the
            // default) just permanently disables its ResponseTime state machine for no benefit -
            // no fast mode for bulk transfers, no low-power idle mode. Let it keep managing them;
            // BlueZ/the kernel central honours standard L2CAP param-update requests by default.
            phoneManagesConnectionParams = false,
            // On production Obelix firmware this watch takes the reversed-PPoG path, where
            // forward is the fragile fallback (see GattServer.jvm.kt's non-self-healing history) -
            // falling back to it on a reversed setup failure trades a working transport for a
            // broken one. Fail the connect instead and let the normal retry loop re-run reversed
            // setup fresh.
            fallbackToForwardPpogOnReversedSetupFailure = false,
        )
    }
    singleOf(::JvmClassicScanner) bind ClassicScanner::class
}
