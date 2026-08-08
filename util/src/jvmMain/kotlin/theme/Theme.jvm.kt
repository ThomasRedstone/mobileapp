package theme

import androidx.compose.runtime.Composable

// Desktop windows have no OS status bar to theme.
@Composable
actual fun setStatusBarTheme(colorScheme: CoreAppColorScheme) {
}
