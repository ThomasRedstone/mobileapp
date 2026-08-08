package coredevices.ring.audio

// See M4aEncoder.jvm.kt — no AAC/M4A codec is available on the JVM target.
actual class M4aDecoder {
    actual suspend fun decode(m4aBytes: ByteArray): DecodedAudio =
        throw UnsupportedOperationException("M4A decoding is not available on the JVM target")
}
