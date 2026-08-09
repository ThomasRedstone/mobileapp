package coredevices.coreapp.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GoogleOAuthTest {

    @Test
    fun `auth url carries the PKCE and loopback parameters Google requires`() {
        val url = GoogleOAuth.buildAuthUrl(
            clientId = "client-123.apps.googleusercontent.com",
            redirectUri = "http://127.0.0.1:41234/oauth2callback",
            codeChallenge = "challenge-value",
            state = "state-value",
        )

        assertTrue(url.startsWith("${GoogleOAuth.AUTH_ENDPOINT}?"))
        val params = url.substringAfter('?').split("&")
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("client-123.apps.googleusercontent.com", params["client_id"])
        assertEquals("http://127.0.0.1:41234/oauth2callback", params["redirect_uri"])
        assertEquals("code", params["response_type"])
        assertEquals("openid email profile", params["scope"])
        assertEquals("challenge-value", params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("state-value", params["state"])
    }

    @Test
    fun `code challenge is the base64url sha256 of the verifier, unpadded`() {
        // RFC 7636 appendix B's worked example.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.codeChallengeOf(verifier))
    }

    @Test
    fun `random strings are url safe and not repeated`() {
        val first = GoogleOAuth.randomUrlSafeString()
        val second = GoogleOAuth.randomUrlSafeString()

        assertNotEquals(first, second)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' }, first)
    }

    @Test
    fun `redirect with a matching state yields the code`() {
        val result = GoogleOAuth.parseRedirect("/oauth2callback?state=abc&code=4%2F0Axyz", "abc")

        assertEquals(GoogleOAuth.RedirectResult.Success("4/0Axyz"), result)
    }

    @Test
    fun `a mismatched state is rejected before the code is read`() {
        val result = GoogleOAuth.parseRedirect("/oauth2callback?state=other&code=4%2F0Axyz", "abc")

        assertEquals(GoogleOAuth.RedirectResult.Failure("state mismatch"), result)
    }

    @Test
    fun `declining consent reads as cancellation, not failure`() {
        val result = GoogleOAuth.parseRedirect("/oauth2callback?error=access_denied&state=abc", "abc")

        assertEquals(GoogleOAuth.RedirectResult.Cancelled, result)
    }

    @Test
    fun `other oauth errors are surfaced`() {
        val result = GoogleOAuth.parseRedirect("/oauth2callback?error=invalid_scope&state=abc", "abc")

        assertEquals(GoogleOAuth.RedirectResult.Failure("invalid_scope"), result)
    }

    @Test
    fun `a redirect with neither code nor error fails`() {
        val result = GoogleOAuth.parseRedirect("/oauth2callback?state=abc", "abc")

        assertEquals(GoogleOAuth.RedirectResult.Failure("no code in redirect"), result)
    }

    @Test
    fun `a bare request with no query is treated as cancelled`() {
        assertEquals(GoogleOAuth.RedirectResult.Cancelled, GoogleOAuth.parseRedirect("/oauth2callback", "abc"))
    }

    @Test
    fun `the loopback listener completes when the browser follows the redirect`() = runBlocking {
        val loopback = GoogleOAuth.startLoopbackServer(expectedState = "state-1")
        try {
            assertTrue(loopback.redirectUri.startsWith("http://127.0.0.1:"), loopback.redirectUri)

            val connection = URI("${loopback.redirectUri}?state=state-1&code=auth-code-1")
                .toURL().openConnection() as HttpURLConnection
            val page = connection.inputStream.bufferedReader().readText()

            assertEquals(200, connection.responseCode)
            assertTrue(page.contains("close this window"), page)
            assertEquals(
                GoogleOAuth.RedirectResult.Success("auth-code-1"),
                withTimeout(5.seconds) { loopback.result.await() },
            )
        } finally {
            loopback.stop()
        }
    }
}
