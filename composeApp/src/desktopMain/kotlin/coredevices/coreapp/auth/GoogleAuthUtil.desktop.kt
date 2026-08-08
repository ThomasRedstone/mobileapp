package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.auth.SilentSignIn
import dev.gitlive.firebase.auth.AuthCredential

// No CredentialManager/GMS equivalent on desktop Linux yet.
actual class RealGoogleAuthUtil : GoogleAuthUtil, SilentSignIn {
    override suspend fun signInGoogle(context: PlatformUiContext): AuthCredential? = null
    override suspend fun attempt(): Boolean = false
}
