package coredevices.pebble.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import coredevices.libindex.device.KnownIndexDevice
import coredevices.pebble.DesktopNotifier
import coredevices.util.Permission
import io.rebble.libpebblecommon.connection.AppContext
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import javax.imageio.ImageIO

actual fun ImageBitmap.toPngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(toAwtImage(), "png", out)
    return out.toByteArray()
}

// Desktop has no coarse-grained scan permission model to request.
actual fun scanPermission(): Permission? = null

actual fun getIPAddress(): Pair<String?, String?> {
    val addresses = NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress }
        .toList()
    val v4 = addresses.filter { it.address.size == 4 }.map { it.hostAddress }.firstOrNull()
    val v6 = addresses.filter { it.address.size == 16 }
        .map { it.hostAddress?.substringBefore("%") }
        .firstOrNull()
    return Pair(v4, v6)
}

// No standard cross-desktop way to deep-link into Bluetooth settings.
actual fun openSystemBluetoothSettings(appContext: AppContext) {
}

actual fun postTestNotification(appContext: AppContext) {
    DesktopNotifier.notify(key = 1000, title = "Test Notification", body = "Test notification from desktop")
}

@Composable
actual fun RemovePairingMenuItem(
    ring: KnownIndexDevice,
    onShowRemoveDialog: () -> Unit,
    onHideMenu: () -> Unit
) {
    DropdownMenuItem(
        text = { Text("Remove") },
        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        onClick = {
            onShowRemoveDialog()
        },
    )
}
