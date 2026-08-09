package coredevices.coreapp.push

import PlatformContext
import coredevices.util.AppDirs
import java.util.UUID
import java.io.File

// No Android ID equivalent on desktop Linux - persist a random UUID the first time this runs.
actual fun PlatformContext.getDeviceId(): String {
    val file = File(AppDirs.dataDir(), "device-id")
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        file.writeText(UUID.randomUUID().toString())
    }
    return file.readText().trim()
}
