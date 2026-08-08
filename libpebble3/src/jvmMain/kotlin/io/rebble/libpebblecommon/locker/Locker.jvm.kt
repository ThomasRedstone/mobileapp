package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import java.io.File

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    val dir = File(System.getProperty("java.io.tmpdir"), "pbw")
    dir.mkdirs()
    return Path(dir.absolutePath)
}

actual fun getLockerPBWCacheLegacyDirectory(context: AppContext): Path? = null