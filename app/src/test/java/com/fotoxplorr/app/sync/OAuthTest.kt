package com.fotoxplorr.app.sync

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OAuth pieces against a local fake token server. No real Google or Dropbox account is involved. */
class OAuthTest {
    private val servers = ArrayList<FakeHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun spec(base: String = "http://127.0.0.1:1", kind: SyncDestinationKind = SyncDestinationKind.GDRIVE) = OAuthSpec(
        kind, "$base/auth", "$base/token", "$base/revoke", OAuthRedirect.GOOGLE_URI,
        listOf("scope" to OAuthSpecs.GOOGLE_SCOPE, "access_type" to "offline", "prompt" to "consent"), "$base/account",
    )

    private fun tokenServer(handler: (SeenRequest, FakeHttpServer, Exchange) -> Unit): FakeHttpServer {
        lateinit var s: FakeHttpServer
        s = FakeHttpServer { ex, req -> handler(req, s, ex) }
        servers += s
        return s
    }

    private fun form(req: SeenRequest): Map<String, String> =
        String(req.body).split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun caller() = HttpCaller(SocketHttpTransport(), allowPlainHttpOnLocal = true, followSameHostRedirects = false)

    // ---- PKCE -------------------------------------------------------------------------------------

    @Test
    fun `rfc 7636 appendix B vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `verifier and state are 43 url-safe characters and differ each time`() {
        val a = Pkce.newVerifier(SecureRandom())
        val b = Pkce.newVerifier(SecureRandom())
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
        assertNotEquals(Pkce.newState(), Pkce.newState())
    }

    // ---- the request and the redirect ------------------------------------------------------------------

    @Test
    fun `the google authorization url carries pkce, state, the one scope and the redirect`() {
        val request = OAuthRequests.build(OAuthSpecs.GOOGLE, "123-abc.apps.googleusercontent.com")
        val uri = URI(request.url)
        assertEquals("accounts.google.com", uri.host)
        val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("code", q["response_type"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(Pkce.challengeOf(request.verifier), q["code_challenge"])
        assertEquals(request.state, q["state"])
        assertEquals("123-abc.apps.googleusercontent.com", q["client_id"])
        assertEquals(OAuthRedirect.GOOGLE_URI, q["redirect_uri"])
        assertEquals("https://www.googleapis.com/auth/drive.file", q["scope"]) // drive.file ONLY, never the whole Drive
        assertEquals("offline", q["access_type"])
        assertFalse("the verifier itself is never in the url", request.url.contains(request.verifier))
        assertFalse(request.url.contains("client_secret"))
    }

    @Test
    fun `the dropbox authorization url asks for offline access and sends no secret`() {
        val request = OAuthRequests.build(OAuthSpecs.DROPBOX, "abc123key")
        val q = URI(request.url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("www.dropbox.com", URI(request.url).host)
        assertEquals("offline", q["token_access_type"])
        assertEquals(OAuthRedirect.DROPBOX_URI, q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
    }

    @Test
    fun `redirect parsing covers code, wrong state, denial, missing code and strangers`() {
        val s = spec()
        val base = OAuthRedirect.GOOGLE_URI
        assertEquals("the-code", (OAuthRedirectParser.parse("$base?code=the-code&state=ST", s, "ST") as OAuthRedirectResult.Code).code)
        assertEquals("a b/c", (OAuthRedirectParser.parse("$base?code=a%20b%2Fc&state=ST", s, "ST") as OAuthRedirectResult.Code).code)
        assertEquals(OAuthRedirectResult.StateMismatch, OAuthRedirectParser.parse("$base?code=x&state=OTHER", s, "ST"))
        assertEquals(OAuthRedirectResult.StateMismatch, OAuthRedirectParser.parse("$base?code=x", s, "ST"))
        assertEquals(OAuthRedirectResult.Denied, OAuthRedirectParser.parse("$base?error=access_denied&state=ST", s, "ST"))
        assertTrue(OAuthRedirectParser.parse("$base?error=server_error&state=ST", s, "ST") is OAuthRedirectResult.Failed)
        assertEquals(OAuthRedirectResult.Missing, OAuthRedirectParser.parse("$base?state=ST", s, "ST"))
        assertEquals(OAuthRedirectResult.Missing, OAuthRedirectParser.parse("https://evil.example/oauth2redirect?code=x&state=ST", s, "ST"))
        assertEquals(OAuthRedirectResult.Missing, OAuthRedirectParser.parse("${OAuthRedirect.SCHEME}:/elsewhere?code=x&state=ST", s, "ST"))
        assertEquals(OAuthRedirectResult.Missing, OAuthRedirectParser.parse("not a uri", s, "ST"))
    }

    // ---- token calls --------------------------------------------------------------------------------

    @Test
    fun `exchange sends the verifier and no client secret and returns the tokens`() {
        val server = tokenServer { req, s, ex ->
            s.reply(ex, 200, """{"access_token":"at-1","expires_in":3599,"refresh_token":"rt-1","token_type":"Bearer"}""".toByteArray())
        }
        val tokens = OAuthClient(spec(server.base), "cid", caller(), clock = { 1_000L }).exchange("the-code", "the-verifier")
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(1_000L + 3_599_000L, tokens.expiresAtMillis)
        val sent = form(server.seen.single())
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("the-code", sent["code"])
        assertEquals("the-verifier", sent["code_verifier"])
        assertEquals("cid", sent["client_id"])
        assertEquals(OAuthRedirect.GOOGLE_URI, sent["redirect_uri"])
        assertFalse(sent.containsKey("client_secret"))
        assertNull(server.seen.single().headers["authorization"])
    }

    @Test
    fun `an exchange without a refresh token is refused`() {
        val server = tokenServer { _, s, ex -> s.reply(ex, 200, """{"access_token":"at","expires_in":10}""".toByteArray()) }
        val e = runCatching { OAuthClient(spec(server.base), "cid", caller()).exchange("c", "v") }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.AUTH, e.failure)
        assertEquals(SyncCopy.NO_LASTING_SIGN_IN, e.message)
    }

    @Test
    fun `invalid_grant on refresh says sign in again`() {
        val server = tokenServer { _, s, ex -> s.reply(ex, 400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""".toByteArray()) }
        val e = runCatching { OAuthClient(spec(server.base), "cid", caller()).refresh("rt") }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.AUTH, e.failure)
        assertEquals(SyncCopy.SIGN_IN_AGAIN, e.message)
        assertEquals(SyncCopy.SIGN_IN_AGAIN, SyncUi.failureLine(SyncRunState.Failed(e.failure, e.message.orEmpty(), SyncTally())))
    }

    @Test
    fun `invalid_client says the id was not accepted and a 5xx is transient`() {
        var code = 401
        val server = tokenServer { _, s, ex ->
            if (code == 401) s.reply(ex, 401, """{"error":"invalid_client"}""".toByteArray()) else s.reply(ex, code)
        }
        val client = OAuthClient(spec(server.base), "cid", caller())
        assertEquals(SyncCopy.CLIENT_ID_REFUSED, (runCatching { client.refresh("rt") }.exceptionOrNull() as SyncException).message)
        code = 503
        assertEquals(SyncFailure.TRANSIENT, (runCatching { client.refresh("rt") }.exceptionOrNull() as SyncException).failure)
    }

    @Test
    fun `the session caches until a minute before expiry, then refreshes, and invalidate forces a refresh`() {
        var n = 0
        val server = tokenServer { _, s, ex -> s.reply(ex, 200, """{"access_token":"at-${++n}","expires_in":3600}""".toByteArray()) }
        var now = 0L
        val session = OAuthSession(OAuthClient(spec(server.base), "cid", caller(), { now }), "rt".toCharArray(), { now })
        assertEquals("at-1", session.accessToken())
        assertEquals("at-1", session.accessToken())
        now = 3_539_000L
        assertEquals("at-1", session.accessToken())
        now = 3_541_000L
        assertEquals("at-2", session.accessToken())
        session.invalidate()
        assertEquals("at-3", session.accessToken())
        assertTrue(server.seen.all { form(it)["refresh_token"] == "rt" })
    }

    @Test
    fun `the caller's refresh token buffer is zeroed`() {
        val server = tokenServer { _, s, ex -> s.reply(ex, 200, """{"access_token":"a","expires_in":3600}""".toByteArray()) }
        val buffer = "rt-secret".toCharArray()
        OAuthSession(OAuthClient(spec(server.base), "cid", caller()), buffer)
        assertTrue(buffer.all { it == '\u0000' })
    }

    @Test
    fun `account label is read for google and dropbox and is best effort`() {
        val g = tokenServer { _, s, ex -> s.reply(ex, 200, """{"user":{"displayName":"Ada","emailAddress":"ada@example.org"}}""".toByteArray()) }
        assertEquals("ada@example.org", OAuthClient(spec(g.base), "cid", caller()).accountLabel("at"))
        assertEquals("Bearer at", g.seen.single().headers["authorization"])
        val d = tokenServer { _, s, ex -> s.reply(ex, 200, """{"email":"ada@dropbox.example","name":{"display_name":"Ada"}}""".toByteArray()) }
        assertEquals("ada@dropbox.example", OAuthClient(spec(d.base, SyncDestinationKind.DROPBOX), "cid", caller()).accountLabel("at"))
        val bad = tokenServer { _, s, ex -> s.reply(ex, 500) }
        assertNull(OAuthClient(spec(bad.base), "cid", caller()).accountLabel("at"))
    }

    @Test
    fun `google revoke posts the refresh token, dropbox revokes a fresh access token`() {
        val g = tokenServer { _, s, ex -> s.reply(ex, 200) }
        OAuthClient(spec(g.base), "cid", caller()).revoke("rt-g")
        assertEquals("/revoke", g.seen.single().path)
        assertEquals("rt-g", form(g.seen.single())["token"])
        val d = tokenServer { req, s, ex ->
            if (req.path == "/token") s.reply(ex, 200, """{"access_token":"fresh","expires_in":100}""".toByteArray()) else s.reply(ex, 200, "null".toByteArray())
        }
        OAuthClient(spec(d.base, SyncDestinationKind.DROPBOX), "cid", caller()).revoke("rt-d")
        assertEquals(listOf("/token", "/revoke"), d.seen.map { it.path })
        assertEquals("Bearer fresh", d.seen[1].headers["authorization"])
    }

    // ---- the whole sign-in ---------------------------------------------------------------------------

    private fun fakeBrowser(answer: (OAuthRequestView) -> String?) = OAuthBrowser { url -> answer(OAuthRequestView(url)) }

    private class OAuthRequestView(val url: String) {
        val params: Map<String, String> = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val state get() = params.getValue("state")
    }

    private fun accountServer() = tokenServer { req, s, ex ->
        when (req.path) {
            "/token" -> s.reply(ex, 200, """{"access_token":"at","expires_in":3600,"refresh_token":"rt-final"}""".toByteArray())
            "/account" -> s.reply(ex, 200, """{"user":{"emailAddress":"me@example.org"}}""".toByteArray())
            else -> s.reply(ex, 404)
        }
    }

    private fun signIn(server: FakeHttpServer, browser: OAuthBrowser) = OAuthSignIn(
        SocketHttpTransport(), browser, specFor = { spec(server.base) }, io = kotlinx.coroutines.Dispatchers.Unconfined, allowPlainHttpOnLocal = true,
    )

    @Test
    fun `sign in succeeds through the browser, the check of state and the exchange`() {
        val server = accountServer()
        val result = runBlocking {
            signIn(server, fakeBrowser { "${OAuthRedirect.GOOGLE_URI}?code=abc&state=${it.state}" }).signIn(SyncDestinationKind.GDRIVE, " cid ")
        }
        val signed = result.getOrThrow()
        assertEquals("rt-final", String(signed.refreshToken))
        assertEquals("me@example.org", signed.accountLabel)
        val exchange = server.seen.first { it.path == "/token" }
        assertEquals("cid", form(exchange)["client_id"])
        assertEquals("abc", form(exchange)["code"])
    }

    @Test
    fun `a sign in whose reply carries another state is refused and nothing is exchanged`() {
        val server = accountServer()
        val result = runBlocking { signIn(server, fakeBrowser { "${OAuthRedirect.GOOGLE_URI}?code=abc&state=forged" }).signIn(SyncDestinationKind.GDRIVE, "cid") }
        assertEquals(SyncFailure.UNSAFE, (result.exceptionOrNull() as SyncException).failure)
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `denied, cancelled and empty client id are said in words`() {
        val server = accountServer()
        val denied = runBlocking { signIn(server, fakeBrowser { "${OAuthRedirect.GOOGLE_URI}?error=access_denied&state=${it.state}" }).signIn(SyncDestinationKind.GDRIVE, "cid") }
        assertEquals(SyncCopy.SIGN_IN_DENIED, denied.exceptionOrNull()?.message)
        val cancelled = runBlocking { signIn(server, fakeBrowser { null }).signIn(SyncDestinationKind.GDRIVE, "cid") }
        assertEquals(SyncCopy.SIGN_IN_CANCELLED, cancelled.exceptionOrNull()?.message)
        val empty = runBlocking { signIn(server, fakeBrowser { error("no browser for an empty id") }).signIn(SyncDestinationKind.GDRIVE, "  ") }
        assertEquals(SyncCopy.NEED_CLIENT_ID, empty.exceptionOrNull()?.message)
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `an unreadable account name does not fail the sign in`() {
        val server = tokenServer { req, s, ex ->
            if (req.path == "/token") s.reply(ex, 200, """{"access_token":"at","expires_in":3600,"refresh_token":"rt"}""".toByteArray()) else s.reply(ex, 500)
        }
        val signed = runBlocking { signIn(server, fakeBrowser { "${OAuthRedirect.GOOGLE_URI}?code=abc&state=${it.state}" }).signIn(SyncDestinationKind.GDRIVE, "cid") }.getOrThrow()
        assertEquals(SyncCopy.SIGNED_IN_FALLBACK, signed.accountLabel)
    }

    // ---- the rendezvous and the manifest ---------------------------------------------------------

    @Test
    fun `the rendezvous delivers to the waiting sign in once and drops strays`() {
        assertFalse(OAuthRendezvous.deliver("stray"))
        val waiting: CompletableDeferred<String> = OAuthRendezvous.begin()
        assertTrue(OAuthRendezvous.deliver("the-redirect"))
        assertEquals("the-redirect", runBlocking { waiting.await() })
        assertFalse(OAuthRendezvous.deliver("second"))
        val first = OAuthRendezvous.begin()
        val second = OAuthRendezvous.begin()
        assertTrue("a newer sign-in cancels the older", first.isCancelled)
        OAuthRendezvous.abandon(second)
        assertFalse(OAuthRendezvous.deliver("late"))
    }

    @Test
    fun `the connect manifest catches exactly the redirect scheme and the offline manifest has no oauth`() {
        val connect = File("src/connect/AndroidManifest.xml").readText()
        assertTrue(connect.contains("android:scheme=\"${OAuthRedirect.SCHEME}\""))
        assertTrue(connect.contains("OAuthRedirectActivity"))
        assertTrue(OAuthRedirect.SCHEME.contains('.'))
        assertTrue(OAuthRedirect.GOOGLE_URI.startsWith("${OAuthRedirect.SCHEME}:/") && !OAuthRedirect.GOOGLE_URI.startsWith("${OAuthRedirect.SCHEME}://"))
        listOf("src/main/AndroidManifest.xml", "src/offline/AndroidManifest.xml").map(::File).filter { it.exists() }.forEach {
            assertFalse(it.name + " must not mention oauth", it.readText().contains("oauth", ignoreCase = true) && it.readText().contains(OAuthRedirect.SCHEME))
        }
    }

    @Test
    fun `tokens never reach the config text and a client id does`() {
        val config = SyncDestinationConfig(
            "id1", SyncDestinationKind.GDRIVE, "Google Drive (me@example.org)", oauth = OAuthSettings("cid.apps.googleusercontent.com", "me@example.org"),
            selection = SyncSelection(allPhotos = true),
        )
        val text = SyncConfigCodec.encode(config)
        assertFalse(text.contains("rt-secret"))
        assertEquals(config, SyncConfigCodec.decode(text))
        assertTrue(SyncConfigCodec.decode(text)!!.oauth!!.signedIn)
        assertFalse(SyncConfigCodec.decode(SyncConfigCodec.encode(config.copy(oauth = OAuthSettings("c", ""))))!!.oauth!!.signedIn)
    }

    @Test
    fun `the mini json reads nested answers and rejects damage`() {
        val v = MiniJson.parseObject("""{"a":[1,2.5,"xé\n",true,null],"b":{"c":"d"},"n":-3}""")!!
        assertEquals(listOf(1L, 2.5, "xé\n", true, null), v["a"])
        assertEquals(-3L, v["n"])
        assertNull(MiniJson.parseObject("{\"a\":"))
        assertNull(MiniJson.parseObject("[1]"))
        assertEquals("\"a\\u00e9\\\"\"", MiniJson.quote("aé\"", asciiOnly = true))
    }
}
