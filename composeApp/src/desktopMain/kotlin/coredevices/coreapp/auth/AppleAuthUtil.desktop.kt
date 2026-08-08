package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.AppleAuthUtil
import dev.gitlive.firebase.auth.AuthCredential

// No native Sign in with Apple flow on desktop Linux yet.
actual class RealAppleAuthUtil : AppleAuthUtil {
    actual override suspend fun signInApple(context: PlatformUiContext): AuthCredential? = null
}
