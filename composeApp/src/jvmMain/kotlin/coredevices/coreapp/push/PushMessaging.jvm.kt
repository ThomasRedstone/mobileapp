package coredevices.coreapp.push

import PlatformContext
import java.util.UUID
import java.io.File

// No Android ID equivalent on desktop Linux - persist a random UUID the first time this runs.
actual fun PlatformContext.getDeviceId(): String {
    val file = File(System.getProperty("user.home"), ".local/share/coreapp/device-id")
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        file.writeText(UUID.randomUUID().toString())
    }
    return file.readText().trim()
}
