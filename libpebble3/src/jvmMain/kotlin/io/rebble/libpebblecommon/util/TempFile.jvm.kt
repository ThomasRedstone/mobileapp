package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import java.io.File

actual fun getTempFilePath(
    appContext: AppContext,
    name: String,
    subdir: String?,
): Path {
    val base = File(System.getProperty("java.io.tmpdir"))
    val dir = if (subdir == null) base else base.resolve(subdir)
    dir.mkdirs()
    val file = dir.resolve(name)
    file.deleteOnExit()
    return Path(file.absolutePath)
}
