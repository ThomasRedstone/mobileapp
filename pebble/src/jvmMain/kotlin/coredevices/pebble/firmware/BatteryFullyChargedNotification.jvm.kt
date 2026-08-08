package coredevices.pebble.firmware

import coredevices.pebble.DesktopNotifier
import io.rebble.libpebblecommon.connection.AppContext

actual fun postWatchFullyChargedNotification(appContext: AppContext, watchName: String) {
    DesktopNotifier.notify(
        key = WATCH_FULLY_CHARGED_NOTIFICATION_ID,
        title = "Watch Fully Charged",
        body = "$watchName is fully charged",
    )
}

private const val WATCH_FULLY_CHARGED_NOTIFICATION_ID = 1001
