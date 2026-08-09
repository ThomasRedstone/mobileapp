package io.rebble.libpebblecommon.di

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

/**
 * Minimal platformModule for Linux/Ubuntu Touch: only the BLE connection
 * path is real here (see LinuxBleScanner/GattServer.jvm.kt/Pairing.jvm.kt).
 * Phone-integration features (calendar, calls, contacts, music,
 * geolocation, notifications) have no meaningful equivalent on a desktop
 * JVM target, so they're bound to the no-op implementations in
 * LinuxPlatformServices.kt rather than the full Android module's OS
 * integration surface.
 */
actual val platformModule: Module = module {
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
        )
    }
    singleOf(::JvmClassicScanner) bind ClassicScanner::class
}
