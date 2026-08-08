package coredevices.ring.storage

import dev.gitlive.firebase.storage.File
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val ringDataHome = System.getProperty("java.io.tmpdir")?.let { java.io.File(it, "coreapp-ring") }
    ?: java.io.File(System.getProperty("user.home"), ".coreapp-ring")

internal actual fun getRecordingsCacheDirectory(): Path {
    val path = Path(java.io.File(ringDataHome, "cache/recordings").absolutePath)
    SystemFileSystem.createDirectories(path, false)
    return path
}

internal actual fun getRecordingsDataDirectory(): Path {
    val path = Path(java.io.File(ringDataHome, "recordings").absolutePath)
    SystemFileSystem.createDirectories(path, false)
    return path
}

actual fun getFirebaseStorageFile(path: Path): File = File(java.io.File(path.toString()))
