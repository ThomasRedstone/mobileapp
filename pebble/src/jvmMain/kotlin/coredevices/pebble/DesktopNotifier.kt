package coredevices.pebble

import co.touchlab.kermit.Logger
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

// Shared java.awt.SystemTray plumbing for the handful of platform notification actuals
// (battery/firmware update/test notifications). Desktop notifications have no channels or
// actions, so this only covers title/body display and removal by key.
internal object DesktopNotifier {
    private val logger = Logger.withTag("DesktopNotifier")
    private val icons = mutableMapOf<Int, TrayIcon>()

    fun notify(key: Int, title: String, body: String) {
        val tray = SystemTray.getDefault()
        if (tray == null) {
            logger.w { "SystemTray not supported, dropping notification: $title" }
            return
        }
        remove(key)
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val trayIcon = TrayIcon(image, title)
        trayIcon.isImageAutoSize = true
        try {
            tray.add(trayIcon)
            trayIcon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            icons[key] = trayIcon
        } catch (e: Exception) {
            logger.w(e) { "Failed to display desktop notification" }
        }
    }

    fun remove(key: Int) {
        val trayIcon = icons.remove(key) ?: return
        SystemTray.getDefault()?.remove(trayIcon)
    }
}
