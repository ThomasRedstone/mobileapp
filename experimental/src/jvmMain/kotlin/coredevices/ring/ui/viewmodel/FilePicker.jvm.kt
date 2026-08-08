package coredevices.ring.ui.viewmodel

import PlatformUiContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual suspend fun pickZipFile(uiContext: PlatformUiContext): Path? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, "Select backup zip", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        isVisible = true
    }
    val directory = dialog.directory ?: return@withContext null
    val file = dialog.file ?: return@withContext null
    Path(File(directory, file).absolutePath)
}
