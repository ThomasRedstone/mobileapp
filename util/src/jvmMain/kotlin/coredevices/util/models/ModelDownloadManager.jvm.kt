package coredevices.util.models

import co.touchlab.kermit.Logger
import coredevices.util.AppDirs
import coredevices.util.CommonBuildKonfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val MODELS_DIR_NAME = "models"

internal fun modelsDirectory(): File = AppDirs.dataDir(MODELS_DIR_NAME).apply { mkdirs() }

// If the zip's contents were nested under a single top-level directory, hoist them up so
// model files sit directly in the slug directory (matches the layout getSTTModelPath()/etc. expect).
private fun promoteSingleRootDir(outputDir: File) {
    val entries = outputDir.listFiles() ?: return
    if (entries.size == 1 && entries[0].isDirectory) {
        val nested = entries[0]
        nested.listFiles()?.forEach { it.renameTo(File(outputDir, it.name)) }
        nested.delete()
    }
}

actual class ModelDownloadManager {
    private val logger = Logger.withTag("ModelDownloadManager")
    private val _downloadStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    actual val downloadStatus: StateFlow<ModelDownloadStatus> = _downloadStatus.asStateFlow()

    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val cancelled = AtomicBoolean(false)

    // Desktop has no metered-connection concept to honor allowMetered against.
    private fun download(modelInfo: ModelInfo): Boolean {
        if (_downloadStatus.value is ModelDownloadStatus.Downloading) return false
        cancelled.set(false)
        _downloadStatus.value = ModelDownloadStatus.Downloading(modelInfo.slug)
        Thread({ runDownload(modelInfo) }, "model-download-${modelInfo.slug}").start()
        return true
    }

    private fun runDownload(modelInfo: ModelInfo) {
        val outputDir = File(modelsDirectory(), modelInfo.slug)
        try {
            val connection = (URL(modelInfo.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            activeConnection.set(connection)
            connection.connect()
            val totalBytes = connection.contentLengthLong

            outputDir.deleteRecursively()
            outputDir.mkdirs()

            var bytesRead = 0L
            connection.inputStream.use { input ->
                ZipInputStream(input).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outFile = File(outputDir, entry.name)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                var n: Int
                                while (zip.read(buffer).also { n = it } >= 0) {
                                    if (cancelled.get()) throw InterruptedIOException("Download cancelled")
                                    out.write(buffer, 0, n)
                                    bytesRead += n
                                    if (totalBytes > 0) {
                                        _downloadStatus.value = ModelDownloadStatus.Downloading(
                                            modelInfo.slug,
                                            (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            promoteSingleRootDir(outputDir)
            File(outputDir, ".cactus_version").writeText(CommonBuildKonfig.CACTUS_WEIGHTS_VERSION)
            logger.i { "Model ${modelInfo.slug} downloaded and extracted to $outputDir" }
            _downloadStatus.value = ModelDownloadStatus.Idle
        } catch (e: InterruptedIOException) {
            logger.i { "Download cancelled for ${modelInfo.slug}" }
            outputDir.deleteRecursively()
            _downloadStatus.value = ModelDownloadStatus.Cancelled
        } catch (e: Exception) {
            logger.e(e) { "Failed to download model ${modelInfo.slug}" }
            outputDir.deleteRecursively()
            _downloadStatus.value = ModelDownloadStatus.Failed(modelInfo.slug, e.message ?: "Download failed")
        } finally {
            activeConnection.set(null)
        }
    }

    actual fun downloadSTTModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean = download(modelInfo)

    actual fun downloadLanguageModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean = download(modelInfo)

    actual fun cancelDownload() {
        cancelled.set(true)
        activeConnection.get()?.disconnect()
    }
}
