package coredevices.ring.util

import co.touchlab.kermit.Logger
import coredevices.util.AudioEncoding
import io.ktor.utils.io.core.writeFully
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

actual class AudioRecorder : AutoCloseable {
    actual val encoding: AudioEncoding = AUDIO_ENCODING
    actual val sampleRate: Int = SAMPLE_RATE

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE.toFloat(),
        16,
        1,
        2,
        SAMPLE_RATE.toFloat(),
        false,
    )
    private val line: TargetDataLine =
        AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine

    @Volatile
    private var isRecording = false

    actual suspend fun startRecording(): RawSource {
        if (!line.isOpen) line.open(format, BUFFER_BYTES)
        isRecording = true
        line.start()
        return object : RawSource {
            override fun close() {
                isRecording = false
                line.stop()
            }

            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                if (!isRecording) {
                    return -1L // Signal end of stream
                }
                // read() only returns on a frame boundary, so keep the request frame-aligned.
                val toRead = byteCount.coerceAtMost(BUFFER_BYTES.toLong()).toInt() and 1.inv()
                if (toRead == 0) return 0L
                val buffer = ByteArray(toRead)
                val bytesRead = line.read(buffer, 0, toRead)
                if (bytesRead <= 0 || !isRecording) {
                    return -1L // Signal end of stream
                }
                sink.writeFully(buffer, 0, bytesRead)
                return bytesRead.toLong()
            }
        }
    }

    actual suspend fun stopRecording() {
        logger.d { "stopRecording()" }
        isRecording = false
        line.stop()
        line.flush()
    }

    actual override fun close() {
        logger.d { "close()" }
        isRecording = false
        line.stop()
        line.close()
    }

    companion object {
        private val logger = Logger.withTag("AudioRecorder")
        private const val SAMPLE_RATE = 16000
        private val AUDIO_ENCODING = AudioEncoding.PCM_16BIT
        private const val BUFFER_BYTES = 4096
    }
}
