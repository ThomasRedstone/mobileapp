package coredevices.ring.ui.viewmodel

import PlatformUiContext
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files

actual suspend fun writeToDownloads(uiContext: PlatformUiContext, path: Path, mimeType: String) {
    withContext(Dispatchers.IO) {
        val downloads = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
        val source = File(path.toString())
        val target = uniqueTarget(downloads, source.name)
        Files.copy(source.toPath(), target.toPath())
        Logger.withTag("RecordingDetailsViewModel").i { "Wrote ${target.absolutePath}" }
    }
}

private fun uniqueTarget(dir: File, name: String): File {
    val base = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var candidate = File(dir, name)
    var index = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base ($index)$extension")
        index++
    }
    return candidate
}
