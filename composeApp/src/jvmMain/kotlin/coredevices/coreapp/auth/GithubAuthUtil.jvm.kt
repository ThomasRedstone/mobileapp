package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.GitHubAuthUtil
import dev.gitlive.firebase.auth.AuthCredential

// No native GitHub OAuth flow on desktop Linux yet.
actual class RealGithubAuthUtil : GitHubAuthUtil {
    actual override suspend fun signInGithub(context: PlatformUiContext): AuthCredential? = null
}
