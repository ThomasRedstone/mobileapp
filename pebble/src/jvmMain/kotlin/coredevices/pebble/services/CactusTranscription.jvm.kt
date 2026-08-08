package coredevices.pebble.services

import kotlinx.io.files.Path

internal actual fun tempTranscriptionDirectory(): Path =
    Path(System.getProperty("java.io.tmpdir"), "watch-transcription")
