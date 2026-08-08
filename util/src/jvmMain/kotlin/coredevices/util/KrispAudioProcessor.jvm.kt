package coredevices.util

// Krisp noise suppression has no desktop model asset bundled (same story as krisp-stubs).
internal actual fun loadModelBlob(): ByteArray = ByteArray(0)
