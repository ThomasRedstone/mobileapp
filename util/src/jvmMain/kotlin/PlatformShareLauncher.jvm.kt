import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.io.files.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.io.File

// Desktop Linux has no share sheet; opening the file with the user's default handler is the
// closest equivalent (e.g. an audio player for a recording).
actual class PlatformShareLauncher {
    actual fun share(text: String?, file: Path) {
        openWithDefaultApplication(File(file.toString()))
    }

    actual fun shareImage(image: ImageBitmap, filename: String) {
        val data = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG) ?: return
        val dir = File(System.getProperty("java.io.tmpdir"), "screenshots").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeBytes(data.bytes)
        openWithDefaultApplication(file)
    }

    private fun openWithDefaultApplication(file: File) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
        }
    }
}
