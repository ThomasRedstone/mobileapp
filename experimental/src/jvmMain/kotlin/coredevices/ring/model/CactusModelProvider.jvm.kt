package coredevices.ring.model

import co.touchlab.kermit.Logger
import com.cactus.cactusSetTelemetryEnvironment
import coredevices.util.CommonBuildKonfig
import coredevices.util.models.promoteSingleRootDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

// Cactus has no native inference engine on the JVM target (see cactus/src/jvmMain);
// model files are still tracked on disk so the rest of the pipeline can resolve paths,
// but nothing will actually be able to run inference against them yet.
actual class CactusModelProvider actual constructor() : coredevices.util.transcription.CactusModelPathProvider {
    companion object {
        private val logger = Logger.withTag("CactusModelProvider")
        private const val HF_BASE = "https://huggingface.co/Cactus-Compute"
        private const val QUANTIZATION = "cq4"

        private val modelMutexes = ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(modelName: String): Mutex =
            modelMutexes.getOrPut(modelName) { Mutex() }
    }

    private val appHome: File by lazy {
        File(System.getProperty("user.home"), ".config/coreapp-ring").apply { mkdirs() }
    }
    private val modelsDir: File get() = File(appHome, "models").apply { mkdirs() }
    private val cacheDir: File get() = File(appHome, "cache").apply { mkdirs() }

    actual override suspend fun getSTTModelPath(): String = withContext(Dispatchers.IO) {
        resolveModelPath(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_WEIGHTS_VERSION)
    }

    actual override suspend fun getLMModelPath(): String = withContext(Dispatchers.IO) {
        resolveModelPath(CommonBuildKonfig.CACTUS_LM_MODEL_NAME, CommonBuildKonfig.CACTUS_WEIGHTS_VERSION)
    }

    actual override fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = modelsDir.resolve(modelName)
        return modelDir.exists() && modelDir.resolve("config.txt").exists()
    }

    actual override fun getDownloadedModels(): List<String> {
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve("config.txt").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    actual override fun getIncompatibleModels(): List<String> {
        val compatible = setOf(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
        return getDownloadedModels().filter { name ->
            modelNeedsReplacement(name, compatible, versionMatches(name), bundledInApp = false)
        }
    }

    private fun versionMatches(modelName: String): Boolean {
        val versionFile = modelsDir.resolve(modelName).resolve(".cactus_version")
        return versionFile.exists() && versionFile.readText().trim() == CommonBuildKonfig.CACTUS_WEIGHTS_VERSION
    }

    actual override fun deleteModel(modelName: String) {
        modelsDir.resolve(modelName).deleteRecursively()
    }

    actual override fun getModelSizeBytes(modelName: String): Long {
        val dir = modelsDir.resolve(modelName)
        return if (dir.exists()) dir.walkTopDown().sumOf { it.length() } else 0L
    }

    private suspend fun resolveModelPath(modelName: String, version: String): String = mutexFor(modelName).withLock {
        val modelDir = modelsDir.resolve(modelName)
        val versionFile = modelDir.resolve(".cactus_version")

        val needsDownload = !modelDir.exists()
            || !modelDir.resolve("config.txt").exists()
            || !versionFile.exists()
            || versionFile.readText().trim() != version

        if (needsDownload) {
            downloadAndExtract(modelName, modelDir, version)
            versionFile.writeText(version)
        }

        logger.d { "Model '$modelName' at: ${modelDir.absolutePath}" }
        modelDir.absolutePath
    }

    private suspend fun downloadAndExtract(modelName: String, targetDir: File, version: String) = withContext(Dispatchers.IO) {
        val zipName = "${modelName.lowercase()}-$QUANTIZATION.zip"
        val url = "$HF_BASE/$modelName/resolve/$version/$zipName"
        logger.i { "Downloading model: $url" }

        val tempZip = File(cacheDir, "cactus_download_$modelName.zip")
        try {
            URI(url).toURL().openStream().use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }
            logger.i { "Download complete: ${tempZip.length()} bytes" }

            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outputFile = File(targetDir, entry.name)
                    if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        throw SecurityException("ZIP entry outside target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            promoteSingleRootDir(Path(targetDir.absolutePath))
            logger.i { "Extraction complete to ${targetDir.absolutePath}" }
        } catch (e: Exception) {
            logger.e(e) { "Model download/extract failed for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } finally {
            tempZip.delete()
        }
    }

    actual fun setCloudApiKey(key: String) {
        File(cacheDir, "cloud_api_key").writeText(key)
        logger.d { "Cloud API key written to cache dir" }
    }

    actual override fun initTelemetry() {
        cactusSetTelemetryEnvironment("kotlin", cacheDir.absolutePath, null)
    }
}
