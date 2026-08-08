package coredevices.ring.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPermissionRequestLauncher(onResult: (Map<String, Boolean>) -> Unit): (input: List<String>) -> Unit {
    // Desktop has no runtime permission model; everything the process can do, it can already do.
    return {
        onResult(it.associateWith { true })
    }
}
