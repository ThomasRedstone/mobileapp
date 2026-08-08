package com.cactus

// No native ML inference on JVM/desktop; isCactusSupported() gates all real usage of the
// functions below, so they only need to fail loudly if called anyway.
actual fun isCactusSupported(): Boolean = false

private fun unsupported(name: String): Nothing =
    throw UnsupportedOperationException("$name is not implemented on the JVM target")

actual fun cactusSetBackend(backend: String): Int = unsupported("cactusSetBackend")
actual fun cactusInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long = unsupported("cactusInit")
actual fun cactusDestroy(handle: Long): Unit = unsupported("cactusDestroy")
actual fun cactusReset(handle: Long): Unit = unsupported("cactusReset")
actual fun cactusStop(handle: Long): Unit = unsupported("cactusStop")

actual fun cactusComplete(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): String =
    unsupported("cactusComplete")

actual fun cactusPrefill(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?, pcmData: ByteArray?): String =
    unsupported("cactusPrefill")

actual fun cactusTokenize(handle: Long, text: String): IntArray = unsupported("cactusTokenize")

actual fun cactusScoreWindow(handle: Long, tokens: IntArray, start: Long, end: Long, context: Long): String =
    unsupported("cactusScoreWindow")

actual fun cactusTranscribe(handle: Long, audioPath: String?, prompt: String, optionsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): String =
    unsupported("cactusTranscribe")

actual fun cactusStreamTranscribeStart(handle: Long, optionsJson: String?): Long = unsupported("cactusStreamTranscribeStart")
actual fun cactusStreamTranscribeProcess(stream: Long, pcmData: ByteArray?): String = unsupported("cactusStreamTranscribeProcess")
actual fun cactusStreamTranscribeStop(stream: Long): String = unsupported("cactusStreamTranscribeStop")

actual fun cactusEmbed(handle: Long, text: String, normalize: Boolean): FloatArray = unsupported("cactusEmbed")
actual fun cactusImageEmbed(handle: Long, imagePath: String): FloatArray = unsupported("cactusImageEmbed")
actual fun cactusAudioEmbed(handle: Long, audioPath: String): FloatArray = unsupported("cactusAudioEmbed")

actual fun cactusRagQuery(handle: Long, query: String, topK: Long): String = unsupported("cactusRagQuery")

actual fun cactusIndexInit(indexDir: String, embeddingDim: Long): Long = unsupported("cactusIndexInit")
actual fun cactusIndexAdd(handle: Long, ids: IntArray, documents: Array<String>, metadatas: Array<String>?, embeddings: Array<FloatArray>, embeddingDim: Long): Int =
    unsupported("cactusIndexAdd")
actual fun cactusIndexDelete(handle: Long, ids: IntArray): Int = unsupported("cactusIndexDelete")
actual fun cactusIndexGet(handle: Long, ids: IntArray): String = unsupported("cactusIndexGet")
actual fun cactusIndexQuery(handle: Long, embedding: FloatArray, optionsJson: String?): String = unsupported("cactusIndexQuery")
actual fun cactusIndexCompact(handle: Long): Int = unsupported("cactusIndexCompact")
actual fun cactusIndexDestroy(handle: Long): Unit = unsupported("cactusIndexDestroy")

// No native error state to report on this platform.
actual fun cactusGetLastError(): String = ""

// Logging config is a no-op; there's no native engine to configure.
actual fun cactusLogSetLevel(level: Int): Unit = Unit
actual fun cactusLogSetCallback(callback: CactusLogCallback?): Unit = Unit

// Telemetry is a no-op; there's no native engine to report from.
actual fun cactusSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?): Unit = Unit
actual fun cactusSetAppId(appId: String): Unit = Unit
actual fun cactusTelemetryFlush(): Unit = Unit
actual fun cactusTelemetryShutdown(): Unit = Unit
