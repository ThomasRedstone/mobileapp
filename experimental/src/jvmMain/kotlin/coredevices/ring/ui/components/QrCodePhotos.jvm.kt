package coredevices.ring.ui.components

import PlatformUiContext
import co.touchlab.kermit.Logger
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

private val logger = Logger.withTag("QrCodePhotos")

private const val QR_IMAGE_SIZE = 1024

actual suspend fun saveQrCodeToPhotos(
    uiContext: PlatformUiContext,
    data: String,
    fileName: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, QR_IMAGE_SIZE, QR_IMAGE_SIZE)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        val pictures = Paths.get(System.getProperty("user.home"), "Pictures")
        Files.createDirectories(pictures)
        val target = pictures.resolve(if (fileName.endsWith(".png")) fileName else "$fileName.png")
        Files.newOutputStream(target).use { ImageIO.write(image, "png", it) }
        true
    } catch (e: Exception) {
        logger.w(e) { "Failed to save QR code to $fileName" }
        false
    }
}

actual suspend fun pickQrCodeFromPhotos(uiContext: PlatformUiContext): QrPhotoPickResult {
    // No photo library on desktop/Ubuntu Touch, and no file picker wired up here.
    logger.d { "Picking a QR code from photos is not supported on desktop" }
    return QrPhotoPickResult.Cancelled
}
