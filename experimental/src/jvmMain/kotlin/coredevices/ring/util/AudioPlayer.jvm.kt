package coredevices.ring.util

import co.touchlab.kermit.Logger
import coredevices.util.AudioEncoding
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

private fun AudioEncoding.toJvmAudioFormat(sampleRate: Int) = when (this) {
    AudioEncoding.PCM_16BIT -> AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRate.toFloat(),
        16,
        1,
        2,
        sampleRate.toFloat(),
        false,
    )
    AudioEncoding.PCM_FLOAT_32BIT -> AudioFormat(
        AudioFormat.Encoding.PCM_FLOAT,
        sampleRate.toFloat(),
        32,
        1,
        4,
        sampleRate.toFloat(),
        false,
    )
}

actual class AudioPlayer actual constructor() : AutoCloseable {
    private val logger = Logger.withTag("AudioPlayer")
    actual val playbackState: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Stopped)

    // Reading the playback Source can throw (e.g. a closed/unreadable Source). Without a handler
    // that would escape the launched coroutine and crash the whole app, so swallow it here.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e(throwable) { "Playback failed" }
        playbackState.value = PlaybackState.Stopped
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var playJob: Job? = null

    @Volatile
    private var line: SourceDataLine? = null

    actual fun playRaw(
        samples: Source,
        sampleRate: Long,
        encoding: AudioEncoding,
        sizeHint: Long
    ) {
        logger.d { "Beginning playback" }
        stop()
        val format = encoding.toJvmAudioFormat(sampleRate.toInt())
        playJob = scope.launch {
            val buffer = ByteArray(sampleRate.toInt() * 2)
            var bytesTotal = 0L
            withLine(format) { line ->
                samples.use { source ->
                    while (isActive && !source.exhausted()) {
                        val bytesRead = source.readAtMostTo(buffer)
                        if (bytesRead <= 0) break
                        line.write(buffer, 0, bytesRead)
                        bytesTotal += bytesRead
                        playbackState.value = PlaybackState.Playing(bytesTotal.toDouble() / sizeHint)
                    }
                }
                if (isActive) line.drain()
            }
        }
    }

    /**
     * Decoding depends on an AAC service provider being on the classpath; the JDK ships none, so
     * this fails cleanly rather than silently producing noise when one isn't installed.
     */
    actual fun playAAC(samples: Source, sampleRate: Long) {
        logger.d { "Beginning AAC playback" }
        stop()
        playJob = scope.launch {
            val bytes = samples.use { it.readByteArray() }
            val encoded = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                encoded.format.sampleRate,
                16,
                encoded.format.channels,
                encoded.format.channels * 2,
                encoded.format.sampleRate,
                false,
            )
            AudioSystem.getAudioInputStream(decodedFormat, encoded).use { decoded ->
                val buffer = ByteArray(decodedFormat.frameSize * 1024)
                var bytesTotal = 0L
                val sizeHint = decoded.frameLength.takeIf { it > 0 }?.times(decodedFormat.frameSize)
                withLine(decodedFormat) { line ->
                    while (isActive) {
                        val bytesRead = decoded.read(buffer)
                        if (bytesRead <= 0) break
                        line.write(buffer, 0, bytesRead)
                        bytesTotal += bytesRead
                        sizeHint?.let {
                            playbackState.value = PlaybackState.Playing(bytesTotal.toDouble() / it)
                        }
                    }
                    if (isActive) line.drain()
                }
            }
        }
    }

    private inline fun withLine(format: AudioFormat, body: (SourceDataLine) -> Unit) {
        val newLine = AudioSystem.getSourceDataLine(format)
        line = newLine
        try {
            newLine.open(format)
            newLine.start()
            newLine.applyPlayerVolume()
            playbackState.value = PlaybackState.Playing(0.0)
            body(newLine)
        } finally {
            runCatching {
                newLine.stop()
                newLine.close()
            }
            if (line === newLine) line = null
            playbackState.value = PlaybackState.Stopped
        }
    }

    private fun SourceDataLine.applyPlayerVolume() {
        if (!isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val control = getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val decibels = 20f * log10(AUDIO_PLAYER_VOLUME)
        control.value = decibels.coerceIn(control.minimum, control.maximum)
    }

    actual fun stop() {
        logger.d { "Stopping" }
        // Flushing unblocks a write() the playback job may be parked in, so cancel() takes effect.
        line?.let { runCatching { it.stop(); it.flush() } }
        playJob?.cancel()
        playbackState.value = PlaybackState.Stopped
    }

    actual override fun close() {
        logger.d { "Closing" }
        stop()
        line?.let { runCatching { it.close() } }
        line = null
    }
}
