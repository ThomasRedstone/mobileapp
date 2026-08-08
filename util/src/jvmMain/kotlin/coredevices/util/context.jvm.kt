package coredevices.util

import PlatformUiContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// No Android Activity on desktop JVM.
@Composable
actual fun getAndroidActivity(): Any? = null

@Composable
actual fun rememberUiContext(): PlatformUiContext? = remember { PlatformUiContext() }
