package coredevices.pebble.audio

// io.github.coredevices.speex publishes no jvm() variant (see docs/ubuntu-touch-poc-plan.md);
// the real implementation lives in mobileMain (android/iOS only). null means "Speex decoding
// unavailable on this platform" - callers should fail the transcription attempt, not crash.
interface SpeexFrameDecoder {
    /** Decodes one frame into [pcmOut], returning true on success. */
    fun decodeFrame(frame: ByteArray, pcmOut: ByteArray, hasHeaderByte: Boolean): Boolean
}

expect fun createSpeexFrameDecoder(sampleRate: Long, bitRate: Int, frameSize: Int): SpeexFrameDecoder?
