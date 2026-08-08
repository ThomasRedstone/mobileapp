package coredevices.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// No embeddable web renderer is available on desktop JVM without an extra dependency
// (e.g. a CEF wrapper); flows that rely on PebbleWebview (OAuth, in-app web content) are
// unsupported on this target for now.
@Composable
actual fun PebbleWebview(
    url: String,
    interceptor: PebbleWebviewUrlInterceptor,
    modifier: Modifier,
    onPageFinishedJavaScript: String?,
    onPageError: ((message: String) -> Unit)?,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Web content isn't supported on this platform yet.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
