package coredevices.pebble.audio

import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult

private class RealSpeexFrameDecoder(
    sampleRate: Long,
    bitRate: Int,
    frameSize: Int,
) : SpeexFrameDecoder {
    private val codec = SpeexCodec(sampleRate = sampleRate, bitRate = bitRate, frameSize = frameSize)

    override fun decodeFrame(frame: ByteArray, pcmOut: ByteArray, hasHeaderByte: Boolean): Boolean =
        codec.decodeFrame(frame, pcmOut, hasHeaderByte = hasHeaderByte) == SpeexDecodeResult.Success
}

actual fun createSpeexFrameDecoder(sampleRate: Long, bitRate: Int, frameSize: Int): SpeexFrameDecoder =
    RealSpeexFrameDecoder(sampleRate, bitRate, frameSize)
