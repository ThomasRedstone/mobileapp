package coredevices.pebble.ui

import PlatformUiContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
actual fun makeTokenClipEntry(token: String): ClipEntry = ClipEntry(StringSelection(token))

// No on-device speech recognizer to enumerate supported languages from on desktop.
actual fun getPlatformSTTLanguages(): List<Pair<String, String>> {
    return listOf("en-US" to "English (US)")
}

actual fun openGoogleFitApp(uiContext: PlatformUiContext?) {
    // Not applicable on desktop - the menu item is only shown on Android.
}
