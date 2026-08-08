package coredevices.pebble.firmware

import coredevices.pebble.DesktopNotifier
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleIdentifier

actual fun notifyFirmwareUpdate(
    appContext: AppContext,
    title: String,
    body: String,
    key: Int,
    identifier: PebbleIdentifier,
) {
    DesktopNotifier.notify(key, title, body)
}

actual fun removeFirmwareUpdateNotification(appContext: AppContext, key: Int) {
    DesktopNotifier.remove(key)
}
