package coredevices.coreapp.auth

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.util.CommonBuildKonfig
import coredevices.util.Platform
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.auth.SilentSignIn
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.GoogleAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

/**
 * Google sign-in via the OAuth 2.0 flow for installed apps: the system browser handles consent
 * and redirects back to a loopback HTTP server this process opens on a random port. Credential
 * Manager / GMS, which Android uses, has no desktop equivalent.
 *
 * This needs an OAuth client of type "Desktop app" (`googleDesktopClientId`/`googleDesktopClientSecret`).
 * The Android `googleClientId` is a Web client, whose redirect URIs must be registered up front
 * including the port - incompatible with the random loopback port this flow uses.
 */
actual class RealGoogleAuthUtil(private val platform: Platform) : GoogleAuthUtil, SilentSignIn {
    private val logger = Logger.withTag("RealGoogleAuthUtil")
    private val httpClient = HttpClient(OkHttp)

    override suspend fun signInGoogle(context: PlatformUiContext): AuthCredential? {
        val clientId = CommonBuildKonfig.GOOGLE_DESKTOP_CLIENT_ID
        val clientSecret = CommonBuildKonfig.GOOGLE_DESKTOP_CLIENT_SECRET
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            throw IllegalStateException(
                "This build has no desktop Google OAuth client " +
                    "(set googleDesktopClientId/googleDesktopClientSecret)"
            )
        }
        val verifier = GoogleOAuth.randomUrlSafeString()
        val state = GoogleOAuth.randomUrlSafeString(bytes = 16)
        val loopback = withContext(Dispatchers.IO) { GoogleOAuth.startLoopbackServer(state) }
        val code = try {
            platform.openUrl(
                GoogleOAuth.buildAuthUrl(
                    clientId = clientId,
                    redirectUri = loopback.redirectUri,
                    codeChallenge = GoogleOAuth.codeChallengeOf(verifier),
                    state = state,
                )
            )
            when (val result = withTimeoutOrNull(AUTH_TIMEOUT) { loopback.result.await() }) {
                is GoogleOAuth.RedirectResult.Success -> result.code
                is GoogleOAuth.RedirectResult.Failure -> error("Google sign-in failed: ${result.reason}")
                GoogleOAuth.RedirectResult.Cancelled -> return null
                null -> {
                    logger.i { "Google sign-in timed out waiting for the browser redirect" }
                    return null
                }
            }
        } finally {
            withContext(Dispatchers.IO) { loopback.stop() }
        }

        val idToken = exchangeCodeForIdToken(
            code = code,
            verifier = verifier,
            redirectUri = loopback.redirectUri,
            clientId = clientId,
            clientSecret = clientSecret,
        )
        return GoogleAuthProvider.credential(idToken, null)
    }

    // Firebase's own persisted session (see DesktopFirebase's FirebasePlatform) restores sign-in
    // across restarts here; there is no separate cached platform credential to fall back on.
    override suspend fun attempt(): Boolean = false

    private suspend fun exchangeCodeForIdToken(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val response = httpClient.submitForm(
            url = GoogleOAuth.TOKEN_ENDPOINT,
            formParameters = Parameters.build {
                append("code", code)
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("redirect_uri", redirectUri)
                append("grant_type", "authorization_code")
                append("code_verifier", verifier)
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // Google puts a machine-readable reason in the body; the status alone is just "400".
            error("Google token exchange failed (${response.status}): $body")
        }
        return Json.parseToJsonElement(body).jsonObject["id_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google token response had no id_token")
    }

    companion object {
        private val AUTH_TIMEOUT = 5.minutes
    }
}
