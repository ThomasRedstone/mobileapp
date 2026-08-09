package coredevices.coreapp.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The pure parts of Google's OAuth 2.0 flow for installed apps (PKCE + a loopback redirect):
 * https://developers.google.com/identity/protocols/oauth2/native-app
 *
 * Separated from [RealGoogleAuthUtil] so the URL building, PKCE derivation and redirect parsing
 * can be tested without a browser or a live token endpoint.
 */
internal object GoogleOAuth {
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    // openid is what makes the token endpoint return an id_token, which is the credential
    // Firebase actually signs in with.
    val SCOPES = listOf("openid", "email", "profile")

    private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun randomUrlSafeString(bytes: Int = 32): String =
        base64Url.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    fun codeChallengeOf(verifier: String): String =
        base64Url.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    fun buildAuthUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
        scopes: List<String> = SCOPES,
    ): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.joinToString(" "),
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        return params.joinToString("&", prefix = "$AUTH_ENDPOINT?") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    sealed interface RedirectResult {
        data class Success(val code: String) : RedirectResult
        /** The user dismissed Google's consent screen, or the browser hit the wrong path. */
        data object Cancelled : RedirectResult
        data class Failure(val reason: String) : RedirectResult
    }

    /**
     * @param requestUri the raw request line target the loopback server received, e.g.
     *   `/callback?code=...&state=...`
     */
    fun parseRedirect(requestUri: String, expectedState: String): RedirectResult {
        val query = URI(requestUri).rawQuery ?: return RedirectResult.Cancelled
        val params = query.split("&")
            .mapNotNull { param ->
                val name = param.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null
                else name to urlDecode(param.substringAfter('=', missingDelimiterValue = ""))
            }
            .toMap()

        // Checked before anything else: a mismatched state means this redirect isn't ours.
        val state = params["state"]
        if (state != expectedState) return RedirectResult.Failure("state mismatch")

        params["error"]?.let { error ->
            return if (error == "access_denied") RedirectResult.Cancelled
            else RedirectResult.Failure(error)
        }
        val code = params["code"] ?: return RedirectResult.Failure("no code in redirect")
        return RedirectResult.Success(code)
    }

    private fun urlDecode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")

    class Loopback(
        private val server: HttpServer,
        val redirectUri: String,
        val result: CompletableDeferred<RedirectResult>,
    ) {
        fun stop() = server.stop(0)
    }

    /**
     * Binds a redirect listener on a free loopback port. Google allows any port for installed-app
     * clients, so nothing needs registering up front.
     */
    fun startLoopbackServer(expectedState: String, path: String = REDIRECT_PATH): Loopback {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val result = CompletableDeferred<RedirectResult>()
        server.createContext(path) { exchange ->
            val parsed = parseRedirect(exchange.requestURI.toString(), expectedState)
            respond(exchange, parsed)
            result.complete(parsed)
        }
        server.start()
        return Loopback(
            server = server,
            redirectUri = "http://127.0.0.1:${server.address.port}$path",
            result = result,
        )
    }

    const val REDIRECT_PATH = "/oauth2callback"

    private fun respond(exchange: HttpExchange, result: RedirectResult) {
        val message = when (result) {
            is RedirectResult.Success -> "Signed in. You can close this window."
            RedirectResult.Cancelled -> "Sign-in cancelled. You can close this window."
            is RedirectResult.Failure -> "Sign-in failed: ${result.reason}"
        }
        val body = "<html><body><p>$message</p></body></html>".toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
