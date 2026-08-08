package coredevices.coreapp.ui.screens

import java.io.File

actual fun isThirdPartyTest(): Boolean = false

actual fun getExperimentalDebugInfoDirectory(): String {
    val path = File(System.getProperty("java.io.tmpdir")).resolve("haversine_debug")
    path.mkdirs()
    return path.absolutePath
}
