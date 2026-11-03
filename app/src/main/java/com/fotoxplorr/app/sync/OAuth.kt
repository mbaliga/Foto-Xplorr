package com.fotoxplorr.app.sync

import com.fotoxplorr.app.sync.MiniJson.long
import com.fotoxplorr.app.sync.MiniJson.obj
import com.fotoxplorr.app.sync.MiniJson.str
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Sign-in for Google Drive and Dropbox (batch 9, owner feedback item 8). ADR-style facts, stated once:
 *
 *  - OAuth 2.0 authorization-code flow with PKCE (S256, RFC 7636), in the SYSTEM BROWSER (a Custom Tab if the
 *    phone has one, else a plain browser). No WebView, no embedded sign-in page, no new dependency.
 *  - Connect build only. Nothing starts without an explicit tap on "Sign in"; the Offline build has a stub.
 *  - The app ships NO client credentials. The owner registers their OWN app (Google Cloud console / Dropbox App
 *    Console) and pastes its id into the form (bring your own key). PKCE needs no client secret, and none is
 *    sent. A client id is not a secret and is saved with the settings; tokens never are.
 *  - Tokens: the short-lived access token lives in memory for the run; the long-lived REFRESH token is the
 *    destination's secret ([SyncSecrets], the Keystore-backed store, id prefixed `sync:`). Neither is logged,
 *    put in the manifest, the config text or saved UI state.
 *  - The redirect is a custom scheme owned by the app ([OAuthRedirect]). Any app could register the same
 *    scheme; what protects the code is PKCE (the code is useless without the verifier, which never leaves
 *    memory) and the `state` check. The redirect URI is never logged (it carries the one-time code).
 *  - UNVERIFIED: today's exact rules of Google for custom-scheme redirects on installed apps. Google's own page
 *    says the scheme must be reverse-DNS notation (contain a period) with a single slash before the path, and
 *    that a client of type Android / iOS needs no client secret; it also says custom schemes are no longer
 *    supported for Android clients, and says nothing here about which scheme an iOS-type client will accept.
 *    The scheme is therefore ONE constant ([OAuthRedirect.SCHEME]) that the connect manifest repeats (a test
 *    keeps the two equal). A real sign-in on a phone is the only proof.
 *  - Nothing here has talked to Google or Dropbox.
 */

/** The app-owned redirect. One constant, so a rule change in a provider's console is a one-line edit. */
object OAuthRedirect {
    /** Reverse-DNS, contains a period (Google's stated rule). Repeated in `src/connect/AndroidManifest.xml`. */
    const val SCHEME = "com.fotoxplorr.app.oauth"
    const val GOOGLE_URI = "$SCHEME:/oauth2redirect"
    const val DROPBOX_URI = "$SCHEME:/dropbox"
}

/** One provider's OAuth endpoints. Production values are in [OAuthSpecs]; tests build their own pointing at a local server. */
class OAuthSpec(
    val kind: SyncDestinationKind,
    val authorizeUrl: String,
    val tokenUrl: String,
    val revokeUrl: String,
    val redirectUri: String,
    /** Fixed extra parameters of the authorization request (scope, offline access, ...). */
    val authParams: List<Pair<String, String>>,
    /** Where the signed-in account's name comes from; reading it is best effort. */
    val accountUrl: String,
)

object OAuthSpecs {
    /** The only Drive scope: files THIS app creates. Never the whole Drive. */
    const val GOOGLE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    val GOOGLE = OAuthSpec(
        kind = SyncDestinationKind.GDRIVE,
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        revokeUrl = "https://oauth2.googleapis.com/revoke",
        redirectUri = OAuthRedirect.GOOGLE_URI,
        // prompt=consent: Google hands out a refresh token only at consent; without it a second sign-in has none.
        authParams = listOf("scope" to GOOGLE_SCOPE, "access_type" to "offline", "prompt" to "consent"),
        accountUrl = "https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress)",
    )

    val DROPBOX = OAuthSpec(
        kind = SyncDestinationKind.DROPBOX,
        authorizeUrl = "https://www.dropbox.com/oauth2/authorize",
        tokenUrl = "https://api.dropboxapi.com/oauth2/token",
        revokeUrl = "https://api.dropboxapi.com/2/auth/token/revoke",
        redirectUri = OAuthRedirect.DROPBOX_URI,
        // No scope parameter: Dropbox then grants the scopes ticked on the app's Permissions tab.
        authParams = listOf("token_access_type" to "offline"),
        accountUrl = "https://api.dropboxapi.com/2/users/get_current_account",
    )

    fun forKind(kind: SyncDestinationKind): OAuthSpec? = when (kind) {
        SyncDestinationKind.GDRIVE -> GOOGLE
        SyncDestinationKind.DROPBOX -> DROPBOX
        else -> null
    }
}

/** PKCE (RFC 7636) and the `state` value. */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes as 43 URL-safe characters (the RFC allows 43 to 128). */
    fun newVerifier(random: SecureRandom = SecureRandom()): String = randomToken(random)

    fun newState(random: SecureRandom = SecureRandom()): String = randomToken(random)

    /** S256: BASE64URL(SHA256(ASCII(verifier))) without padding. */
    fun challengeOf(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomToken(random: SecureRandom): String = ByteArray(32).also { random.nextBytes(it) }.let { encoder.encodeToString(it) }
}

/** The page to open, and what must be kept until the browser comes back. */
class OAuthRequest(val url: String, val verifier: String, val state: String)

object OAuthRequests {
    fun build(spec: OAuthSpec, clientId: String, random: SecureRandom = SecureRandom()): OAuthRequest {
        val verifier = Pkce.newVerifier(random)
        val state = Pkce.newState(random)
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to spec.redirectUri,
            "response_type" to "code",
            "code_challenge" to Pkce.challengeOf(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
        ) + spec.authParams
        return OAuthRequest(spec.authorizeUrl + "?" + OAuthForm.encode(params), verifier, state)
    }
}

object OAuthForm {
    fun encode(params: List<Pair<String, String>>): String = params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    fun enc(text: String): String = WebDavUrl.encodeSegment(text)
}

/** What the browser's return trip amounted to. */
sealed interface OAuthRedirectResult {
    class Code(val code: String) : OAuthRedirectResult
    data object Denied : OAuthRedirectResult
    data object StateMismatch : OAuthRedirectResult
    data object Missing : OAuthRedirectResult
    class Failed(val error: String) : OAuthRedirectResult
}

object OAuthRedirectParser {
    /**
     * [redirect] is the full address the browser handed back. The state is checked FIRST (a reply that does not
     * carry ours is not an answer to our request, whatever else it says); then the provider's error (the person
     * declined, `access_denied`); then the code.
     */
    fun parse(redirect: String, spec: OAuthSpec, expectedState: String): OAuthRedirectResult {
        val uri = try {
            URI(redirect)
        } catch (e: java.net.URISyntaxException) {
            return OAuthRedirectResult.Missing
        }
        val expected = URI(spec.redirectUri)
        if (!uri.scheme.equals(expected.scheme, ignoreCase = true) || (uri.path ?: "") != (expected.path ?: "")) return OAuthRedirectResult.Missing
        val query = HashMap<String, String>()
        for (pair in (uri.rawQuery ?: "").split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            query[decode(key)] = decode(value)
        }
        val state = query["state"]
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return OAuthRedirectResult.StateMismatch
        query["error"]?.let { return if (it == "access_denied") OAuthRedirectResult.Denied else OAuthRedirectResult.Failed(it) }
        val code = query["code"]?.takeIf { it.isNotBlank() } ?: return OAuthRedirectResult.Missing
        return OAuthRedirectResult.Code(code)
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}

/** [accessToken] is short-lived; [refreshToken] is present on the first exchange (and on Dropbox/Google only then). */
class OAuthTokens(val accessToken: String, val expiresAtMillis: Long, val refreshToken: String?)

/**
 * The calls to a provider's token endpoint (and revoke, and the account name). Blocking. A rejected refresh
 * token (`invalid_grant`: revoked, expired, or a Google app still in "Testing" for over seven days) is
 * [SyncFailure.AUTH] with [SyncCopy.SIGN_IN_AGAIN]; the person signs in again, nothing else can fix it.
 */
class OAuthClient(
    private val spec: OAuthSpec,
    private val clientId: String,
    private val caller: HttpCaller,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun exchange(code: String, verifier: String, cancel: Cancellation = Cancellation.NEVER): OAuthTokens {
        val tokens = tokenCall(
            listOf(
                "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
                "client_id" to clientId, "redirect_uri" to spec.redirectUri,
            ),
            cancel,
        )
        if (tokens.refreshToken.isNullOrBlank()) throw SyncException(SyncFailure.AUTH, SyncCopy.NO_LASTING_SIGN_IN)
        return tokens
    }

    fun refresh(refreshToken: String, cancel: Cancellation = Cancellation.NEVER): OAuthTokens =
        tokenCall(listOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId), cancel)

    private fun tokenCall(params: List<Pair<String, String>>, cancel: Cancellation): OAuthTokens {
        val request = HttpRequest(
            "POST", spec.tokenUrl,
            listOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json", "User-Agent" to USER_AGENT),
            bodyBytes = OAuthForm.encode(params).toByteArray(Charsets.UTF_8),
        )
        caller.call(request, cancel).use { response ->
            val body = response.bodyText()
            if (response.code != 200) {
                val error = MiniJson.parseObject(body)?.str("error")
                throw when {
                    error == "invalid_grant" -> SyncException(SyncFailure.AUTH, SyncCopy.SIGN_IN_AGAIN)
                    error == "invalid_client" || error == "unauthorized_client" || error == "invalid_request" && response.code == 400 ->
                        SyncException(SyncFailure.AUTH, SyncCopy.CLIENT_ID_REFUSED)
                    else -> HttpStatus.failureFor(response.code, SyncCopy.SIGN_IN_FAILED)
                }
            }
            val json = MiniJson.parseObject(body) ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_FAILED)
            val access = json.str("access_token")?.takeIf { it.isNotBlank() } ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_FAILED)
            val seconds = json.long("expires_in") ?: 3600L
            return OAuthTokens(access, clock() + seconds * 1000L, json.str("refresh_token"))
        }
    }

    /** Best-effort name of the account ("name <mail>"), or null. */
    fun accountLabel(accessToken: String, cancel: Cancellation = Cancellation.NEVER): String? = try {
        val headers = listOf("Authorization" to "Bearer $accessToken", "User-Agent" to USER_AGENT)
        val request = if (spec.kind == SyncDestinationKind.GDRIVE) {
            HttpRequest("GET", spec.accountUrl, headers)
        } else {
            HttpRequest("POST", spec.accountUrl, headers, bodyBytes = ByteArray(0))
        }
        caller.call(request, cancel).use { response ->
            if (response.code != 200) return null
            val json = MiniJson.parseObject(response.bodyText())
            val mail: String?
            val name: String?
            if (spec.kind == SyncDestinationKind.GDRIVE) {
                mail = json.obj("user")?.str("emailAddress")
                name = json.obj("user")?.str("displayName")
            } else {
                mail = json?.str("email")
                name = json.obj("name")?.str("display_name")
            }
            (mail ?: name)?.takeIf { it.isNotBlank() }
        }
    } catch (e: SyncException) {
        null
    }

    /**
     * Tells the provider to forget this grant. Google revokes the refresh token itself; Dropbox revokes the
     * access token it is called with, so a fresh one is fetched first. Throws on trouble; the caller ignores it.
     */
    fun revoke(refreshToken: String, cancel: Cancellation = Cancellation.NEVER) {
        val request = if (spec.kind == SyncDestinationKind.GDRIVE) {
            HttpRequest(
                "POST", spec.revokeUrl,
                listOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to USER_AGENT),
                bodyBytes = OAuthForm.encode(listOf("token" to refreshToken)).toByteArray(Charsets.UTF_8),
            )
        } else {
            val access = refresh(refreshToken, cancel).accessToken
            HttpRequest("POST", spec.revokeUrl, listOf("Authorization" to "Bearer $access", "User-Agent" to USER_AGENT), bodyBytes = ByteArray(0))
        }
        caller.call(request, cancel).close()
    }

    private companion object {
        const val USER_AGENT = "Fotoz-sync"
    }
}

/**
 * Hands a provider a valid access token: the cached one while it has more than a minute left, else a refreshed
 * one. [invalidate] (after a 401) forces the next call to refresh. Thread-safe. The refresh token is held as a
 * String for the life of the provider (the platform gives no way to wipe a String; the caller's CharArray is
 * zeroed at once).
 */
class OAuthSession(private val client: OAuthClient, refreshToken: CharArray, private val clock: () -> Long = System::currentTimeMillis) {
    private val refreshToken: String = String(refreshToken)
    private var cached: OAuthTokens? = null

    init {
        refreshToken.fill('\u0000')
        if (this.refreshToken.isBlank()) throw SyncException(SyncFailure.AUTH, SyncCopy.SIGN_IN_AGAIN)
    }

    @Synchronized
    fun accessToken(cancel: Cancellation = Cancellation.NEVER): String {
        val have = cached
        if (have != null && have.expiresAtMillis - clock() > SKEW_MILLIS) return have.accessToken
        val fresh = client.refresh(refreshToken, cancel)
        cached = fresh
        return fresh.accessToken
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    private companion object {
        const val SKEW_MILLIS = 60_000L
    }
}

/** What a finished sign-in hands the settings screen. [refreshToken] becomes the destination's secret on Save. */
class OAuthSignedIn(val refreshToken: CharArray, val accountLabel: String)

/** The sign-in as the settings screen sees it. Always from an explicit tap. */
interface SyncOAuth {
    suspend fun signIn(kind: SyncDestinationKind, clientId: String): Result<OAuthSignedIn>
}

/** Used where there is no network (the Offline build). */
object UnavailableSyncOAuth : SyncOAuth {
    override suspend fun signIn(kind: SyncDestinationKind, clientId: String): Result<OAuthSignedIn> =
        Result.failure(SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD))
}

/** Opens [url] in the system browser and returns the address the browser came back with, or null (cancelled, timed out, no browser). */
fun interface OAuthBrowser {
    suspend fun authorize(url: String): String?
}

/**
 * The meeting point between the browser's return (an activity that receives the redirect) and the suspended
 * sign-in that is waiting for it. In memory only: if the process dies while the browser is open the sign-in is
 * simply over and the person taps Sign in again. One sign-in at a time; a newer one cancels the older.
 */
object OAuthRendezvous {
    private var pending: CompletableDeferred<String>? = null

    @Synchronized
    fun begin(): CompletableDeferred<String> {
        pending?.cancel()
        return CompletableDeferred<String>().also { pending = it }
    }

    /** True when a sign-in was waiting for [redirect]. */
    @Synchronized
    fun deliver(redirect: String): Boolean {
        val waiting = pending ?: return false
        pending = null
        return waiting.complete(redirect)
    }

    /** Drops [waiting] if it is still the pending one (the sign-in that owned it ended without an answer). */
    @Synchronized
    fun abandon(waiting: CompletableDeferred<String>) {
        if (pending === waiting) pending = null
        waiting.cancel()
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}

/** The whole sign-in: request -> browser -> redirect check -> code exchange -> account name. */
class OAuthSignIn(
    private val transport: HttpTransport,
    private val browser: OAuthBrowser,
    private val specFor: (SyncDestinationKind) -> OAuthSpec? = OAuthSpecs::forKind,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val allowPlainHttpOnLocal: Boolean = false,
) : SyncOAuth {
    override suspend fun signIn(kind: SyncDestinationKind, clientId: String): Result<OAuthSignedIn> {
        val spec = specFor(kind) ?: return Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION))
        val id = clientId.trim()
        if (id.isEmpty()) return Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.NEED_CLIENT_ID))
        val request = OAuthRequests.build(spec, id, random)
        val redirect = browser.authorize(request.url) ?: return Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_CANCELLED))
        val code = when (val parsed = OAuthRedirectParser.parse(redirect, spec, request.state)) {
            is OAuthRedirectResult.Code -> parsed.code
            OAuthRedirectResult.Denied -> return Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_DENIED))
            OAuthRedirectResult.StateMismatch -> return Result.failure(SyncException(SyncFailure.UNSAFE, SyncCopy.SIGN_IN_STATE))
            OAuthRedirectResult.Missing, is OAuthRedirectResult.Failed -> return Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_FAILED))
        }
        return withContext(io) {
            try {
                val client = OAuthClient(spec, id, HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = false), clock)
                val tokens = client.exchange(code, request.verifier)
                val label = client.accountLabel(tokens.accessToken) ?: SyncCopy.SIGNED_IN_FALLBACK
                Result.success(OAuthSignedIn(tokens.refreshToken!!.toCharArray(), label))
            } catch (e: SyncException) {
                Result.failure(e)
            }
        }
    }
}

/** Forgets a grant at the provider. Called only from the person's tap on Sign out; failures are the caller's to ignore. */
fun interface OAuthRevoker {
    fun revoke(kind: SyncDestinationKind, clientId: String, refreshToken: CharArray)

    companion object {
        val NONE = OAuthRevoker { _, _, token -> token.fill('\u0000') }
    }
}

class DefaultOAuthRevoker(
    private val transport: HttpTransport,
    private val specFor: (SyncDestinationKind) -> OAuthSpec? = OAuthSpecs::forKind,
    private val allowPlainHttpOnLocal: Boolean = false,
) : OAuthRevoker {
    override fun revoke(kind: SyncDestinationKind, clientId: String, refreshToken: CharArray) {
        val text = String(refreshToken)
        refreshToken.fill('\u0000')
        val spec = specFor(kind) ?: return
        OAuthClient(spec, clientId, HttpCaller(transport, allowPlainHttpOnLocal, followSameHostRedirects = false)).revoke(text)
    }
}

/** Shared by the two providers: attaches the token, and on a 401 renews it once and sends again. */
internal class AuthedHttp(private val caller: HttpCaller, private val session: OAuthSession) {
    /** [build] makes the request for a token. A request with a one-shot stream cannot be replayed: its 401 becomes a transient failure and the engine sends the file again with the renewed token. */
    fun send(cancel: Cancellation, build: (token: String) -> HttpRequest): HttpResponse {
        var response = caller.call(build(session.accessToken(cancel)), cancel)
        if (response.code != 401) return response
        response.close()
        session.invalidate()
        val retry = build(session.accessToken(cancel))
        if (retry.bodyStream != null) throw SyncException(SyncFailure.TRANSIENT, "The sign-in was renewed. Trying again.")
        response = caller.call(retry, cancel)
        if (response.code == 401) {
            response.close()
            throw SyncException(SyncFailure.AUTH, SyncCopy.SIGN_IN_AGAIN)
        }
        return response
    }
}

internal fun retryAfterMillis(response: HttpResponse): Long? = response.header("retry-after")?.trim()?.toLongOrNull()?.let { it * 1000L }
