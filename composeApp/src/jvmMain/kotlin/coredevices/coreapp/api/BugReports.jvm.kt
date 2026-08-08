package coredevices.coreapp.api

import PlatformContext
import co.touchlab.kermit.Logger
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

private val logger = Logger.withTag("BugReports.jvm")

// No deep-link intent system on desktop yet - shows the notification, doesn't wire up the tap
// action to reopen the conversation.
actual fun createNotification(
    platformContext: PlatformContext,
    title: String,
    message: String,
    conversationId: String,
) {
    if (!SystemTray.isSupported()) {
        logger.w { "SystemTray not supported, dropping notification: $title" }
        return
    }
    try {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val trayIcon = TrayIcon(image, "Pebble Support message")
        trayIcon.isImageAutoSize = true
        SystemTray.getSystemTray().add(trayIcon)
        trayIcon.displayMessage("Pebble Support message", message, TrayIcon.MessageType.INFO)
    } catch (e: Exception) {
        logger.w(e) { "Failed to display desktop notification" }
    }
}
