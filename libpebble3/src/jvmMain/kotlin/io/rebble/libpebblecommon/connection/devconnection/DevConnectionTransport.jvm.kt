package io.rebble.libpebblecommon.connection.devconnection

import kotlinx.io.files.Path
import java.io.File
import java.util.UUID

internal actual fun getTempPbwPath(): Path {
    val dir = File(System.getProperty("java.io.tmpdir"), "devconn")
    dir.mkdirs()
    return Path(dir.absolutePath, "devconn-${UUID.randomUUID()}.pbw")
}
