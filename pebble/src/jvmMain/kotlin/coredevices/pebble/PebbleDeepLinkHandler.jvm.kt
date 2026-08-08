package coredevices.pebble

import co.touchlab.kermit.Logger
import com.eygraber.uri.Uri
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = Logger.withTag("PebbleDeepLinkHandler")

// Desktop file pickers hand back plain file:// URIs, not the content:// scheme Android uses -
// the path is already a real filesystem path.
actual fun readNameFromContentUri(appContext: AppContext, uri: Uri): String? = uri.lastPathSegment

actual fun writeFile(appContext: AppContext, uri: Uri): Path? {
    val sourcePath = uri.path?.let(::Path) ?: run {
        logger.w { "writeFile: no path in $uri" }
        return null
    }
    if (!SystemFileSystem.exists(sourcePath)) {
        logger.w { "writeFile: no file at $sourcePath for $uri" }
        return null
    }
    val fileToWrite = getTempFilePath(appContext, "sideloaded_file")
    SystemFileSystem.source(sourcePath).buffered().use { source ->
        SystemFileSystem.sink(fileToWrite).use { sink ->
            source.transferTo(sink)
        }
    }
    return fileToWrite
}
