package coredevices.ring.audio

// The JDK has no built-in AAC/M4A encoder (Android's MediaCodec and iOS's AVFoundation both
// wrap a hardware/OS codec neither of which exists on desktop JVM). Recording upload on this
// platform needs either a bundled AAC encoder or a format change at the upload boundary.
actual class M4aEncoder {
    actual suspend fun encode(samples: ShortArray, sampleRate: Int): ByteArray =
        throw UnsupportedOperationException("M4A encoding is not available on the JVM target")
}
