import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private fun File.toDocumentAttachment(): DocumentAttachment = DocumentAttachment(
    fileName = name,
    mimeType = URLConnection.guessContentTypeFromName(name),
    source = FileInputStream(this).asSource().buffered(),
    size = length(),
)

// JFileChooser must not run on the Compose render thread, so the dialog is shown from a
// dedicated thread and the result is delivered back through onResult.
private fun showFileChooser(configure: JFileChooser.() -> Unit, onResult: (List<DocumentAttachment>?) -> Unit) {
    Thread {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            configure()
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            onResult(chooser.selectedFiles.map { it.toDocumentAttachment() }.ifEmpty { null })
        } else {
            onResult(null)
        }
    }.start()
}

@Composable
actual fun rememberOpenDocumentLauncher(onResult: (List<DocumentAttachment>?) -> Unit): (mimeTypeFilter: List<String>) -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return { _ ->
        // JFileChooser filters by file extension, not MIME type, so mimeTypeFilter is unused.
        showFileChooser({}, currentOnResult)
    }
}

@Composable
actual fun rememberOpenPhotoLauncher(onResult: (List<DocumentAttachment>?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return {
        showFileChooser({
            fileFilter = FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp")
        }, currentOnResult)
    }
}
