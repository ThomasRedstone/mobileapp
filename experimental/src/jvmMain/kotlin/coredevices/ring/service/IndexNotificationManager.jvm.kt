package coredevices.ring.service

import co.touchlab.kermit.Logger

// No desktop notification backend wired up yet; posting is logged and dropped.
actual class PlatformIndexNotificationManager {
    private val logger = Logger.withTag("PlatformIndexNotificationManager")

    actual fun notify(notification: GenericNotification) {
        logger.d { "Notification dropped (no desktop backend): [${notification.id}] ${notification.title}" }
    }

    actual fun cancel(notificationId: Int) {
        logger.d { "Notification cancel ignored (no desktop backend): $notificationId" }
    }
}
