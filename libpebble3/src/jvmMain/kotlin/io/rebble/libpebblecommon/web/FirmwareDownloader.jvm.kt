package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import java.io.File

actual fun getFirmwareDownloadDirectory(context: AppContext): Path {
    val dir = File(System.getProperty("java.io.tmpdir"), "fw")
    dir.mkdirs()
    return Path(dir.absolutePath)
}
