package coredevices.pebble.ui

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import java.io.File

actual fun getTempFwPath(appContext: AppContext): Path {
    val file = File(System.getProperty("java.io.tmpdir"), "temp.pbz")
    file.deleteOnExit()
    return Path(file.absolutePath)
}
