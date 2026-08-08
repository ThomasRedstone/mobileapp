package coredevices.pebble.ui

import PlatformUiContext

// Desktop windows don't have an Android-style "send task to background" affordance.
actual fun moveCurrentTaskToBackground(uiContext: PlatformUiContext?): Boolean = false
