package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import kotlin.uuid.Uuid

internal actual fun webViewFactory(
    params: WebViewFactoryParam,
    uuid: Uuid
): NativeWebView = defaultWebViewFactory(params)

// PKJS local storage persistence relies on injecting a JS interface into the webview
// (see the Android actual); the desktop webview backend has no equivalent hook.
internal actual suspend fun restoreLocalStorage(webView: NativeWebView) {
}

internal actual fun persistLocalStorage(webView: NativeWebView) {
}

@Composable
internal actual fun rememberWebViewFileChooserParams(): PlatformWebViewParams? = null
