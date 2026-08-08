package coredevices.util.models

import coredevices.util.Platform

// Desktop JVM has no NPU, and cactus only ships a stub (no real local ML) here, so local
// transcription is never recommended over remote.
actual fun Platform.supportsNPU(): Boolean = false
actual fun Platform.supportsHeavyCPU(): Boolean = false
