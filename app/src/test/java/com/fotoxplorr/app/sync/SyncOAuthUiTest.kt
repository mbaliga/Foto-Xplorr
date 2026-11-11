package com.fotoxplorr.app.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pure side of the Google Drive / Dropbox settings: validation, config building, the gate, sign-out in the service. */
class SyncOAuthStateTest {
    private val google = SyncForm(kind = SyncDestinationKind.GDRIVE, oauthClientId = "123-abc.apps.googleusercontent.com", allPhotos = true)
    private val dropbox = SyncForm(kind = SyncDestinationKind.DROPBOX, oauthClientId = "abc123def456ghi", allPhotos = true)

    @Test
    fun `client id rules are gentle and specific`() {
        assertNull(SyncUi.validate(google, SyncSecretFacts(signedIn = true), true).oauthClientId)
        assertEquals(SyncCopy.NEED_CLIENT_ID, SyncUi.validate(SyncForm(kind = SyncDestinationKind.GDRIVE), SyncSecretFacts(), true).oauthClientId)
        assertNull(SyncUi.validate(SyncForm(kind = SyncDestinationKind.GDRIVE), SyncSecretFacts(), false).oauthClientId)
        assertEquals(SyncCopy.BAD_GOOGLE_CLIENT_ID, SyncUi.validate(google.copy(oauthClientId = "just-an-id"), SyncSecretFacts(), false).oauthClientId)
        assertEquals(SyncCopy.BAD_GOOGLE_CLIENT_ID, SyncUi.validate(google.copy(oauthClientId = "a b.apps.googleusercontent.com"), SyncSecretFacts(), false).oauthClientId)
        assertNull(SyncUi.validate(dropbox, SyncSecretFacts(signedIn = true), true).oauthClientId)
        assertEquals(SyncCopy.BAD_APP_KEY, SyncUi.validate(dropbox.copy(oauthClientId = "not a key!"), SyncSecretFacts(), false).oauthClientId)
    }

    @Test
    fun `sending needs a sign in`() {
        val kinds = SyncDestinationKind.entries.toSet()
        val notSigned = SyncUi.validate(google, SyncSecretFacts(), true)
        assertEquals(SyncCopy.NEED_SIGN_IN, notSigned.secret)
        assertEquals(SyncCopy.FIX_DETAILS_FIRST, SyncUi.startGate(kinds, google, notSigned, SyncSecretFacts(), SyncRunState.Idle).reason)
        val signed = SyncUi.validate(google, SyncSecretFacts(signedIn = true), true)
        assertTrue(SyncUi.startGate(kinds, google, signed, SyncSecretFacts(signedIn = true), SyncRunState.Idle).enabled)
        assertFalse("not supported means no", SyncUi.startGate(setOf(SyncDestinationKind.FOLDER), google, signed, SyncSecretFacts(signedIn = true), SyncRunState.Idle).enabled)
    }

    @Test
    fun `the config built from the form keeps the id for the same client and changes it for another`() {
        val first = SyncUi.buildConfig(google.copy(oauthAccount = "me@example.org"), null) { "one" }!!
        assertEquals("one", first.id)
        assertEquals(OAuthSettings("123-abc.apps.googleusercontent.com", "me@example.org"), first.oauth)
        assertEquals("Google Drive (me@example.org)", first.displayName)
        assertEquals("one", SyncUi.buildConfig(google.copy(oauthAccount = "other@example.org"), first) { "two" }!!.id)
        assertEquals("two", SyncUi.buildConfig(google.copy(oauthClientId = "999-z.apps.googleusercontent.com"), first) { "two" }!!.id)
        assertNull(SyncUi.buildConfig(google.copy(oauthClientId = "nope"), null) { "x" })
        assertEquals("Dropbox", SyncUi.buildConfig(dropbox, null) { "d" }!!.displayName)
    }

    @Test
    fun `the saveable form holds the client id and account but no token field exists`() {
        val form = google.copy(oauthAccount = "me@example.org")
        assertEquals(form, SyncForm.fromSaveable(form.toSaveable()))
        assertEquals(form, SyncForm.from(SyncUi.buildConfig(form, null) { "x" }))
        assertTrue(SyncForm::class.java.declaredFields.none { it.name.contains("token", ignoreCase = true) || it.name.contains("secret", ignoreCase = true) })
        // An older saved state without the two new entries still restores.
        assertEquals(google.copy(oauthClientId = "", oauthAccount = ""), SyncForm.fromSaveable(google.toSaveable().take(19)))
    }

    @Test
    fun `an own auth sentence is shown as written, other auth failures keep the generic one`() {
        fun line(m: String) = SyncUi.failureLine(SyncRunState.Failed(SyncFailure.AUTH, m, SyncTally()))
        assertEquals(SyncCopy.SIGN_IN_AGAIN, line(SyncCopy.SIGN_IN_AGAIN))
        assertEquals(SyncCopy.failureSentence(SyncFailure.AUTH), line("The server refused the request (HTTP 401)"))
    }

    // ---- sign-out in the service ----------------------------------------------------------------------

    private class MemorySecrets : SyncSecrets {
        val map = HashMap<String, String>()
        override fun get(id: String) = map[id]?.toCharArray()
        override fun put(id: String, value: CharArray) { map[id] = String(value); value.fill('\u0000') }
        override fun remove(id: String) { map.remove(id) }
    }

    private class MemoryConfig(var saved: SyncDestinationConfig? = null) : SyncConfigStore {
        override fun load() = saved
        override fun save(config: SyncDestinationConfig) { saved = config }
        override fun clear() { saved = null }
    }

    private val secrets = MemorySecrets()
    private val configs = MemoryConfig()
    private val revoked = ArrayList<String>()
    private var revokeFails = false
    private var stateAtRevoke: Pair<Boolean, Boolean>? = null

    private fun service() = DefaultSyncService(
        configStore = configs, secrets = secrets, manifestStores = { MemoryManifestStore() }, library = { emptyList() },
        fileSource = { _, _ -> SyncFileSource { null } }, providers = { _, s -> s?.fill('\u0000'); throw SyncException(SyncFailure.REJECTED, "x") },
        network = { SyncNetwork.UNMETERED }, runner = { null }, supportedKinds = SyncDestinationKind.entries.toSet(),
        revoker = { kind, clientId, token ->
            stateAtRevoke = secrets.map.isEmpty() to (configs.saved?.oauth?.signedIn == false)
            revoked += "$kind:$clientId:${String(token)}"
            if (revokeFails) throw SyncException(SyncFailure.TRANSIENT, "offline")
        },
    )

    private val saved = SyncDestinationConfig("d1", SyncDestinationKind.DROPBOX, "Dropbox (me)", oauth = OAuthSettings("key", "me"), selection = SyncSelection(allPhotos = true))

    @Test
    fun `sign out forgets locally first, then revokes, and keeps the rest of the settings`() {
        configs.saved = saved
        secrets.map["d1"] = "refresh-1"
        val s = service()
        runBlocking { s.signOut() }.getOrThrow()
        assertTrue(secrets.map.isEmpty())
        assertEquals(listOf("DROPBOX:key:refresh-1"), revoked)
        assertEquals("the token and the account name were already gone when the provider was asked", true to true, stateAtRevoke)
        assertEquals(OAuthSettings("key", ""), s.config.value!!.oauth)
        assertEquals(SyncSelection(allPhotos = true), s.config.value!!.selection)
        assertEquals(s.config.value, configs.saved)
    }

    @Test
    fun `a failing revoke still leaves the person signed out`() {
        configs.saved = saved
        secrets.map["d1"] = "refresh-1"
        revokeFails = true
        val s = service()
        assertTrue(runBlocking { s.signOut() }.isSuccess)
        assertTrue(secrets.map.isEmpty())
        assertFalse(s.config.value!!.oauth!!.signedIn)
    }

    @Test
    fun `an unsaved sign in is revoked with the kind and id given and zeroed`() {
        val s = service()
        val token = "unsaved-token".toCharArray()
        runBlocking { s.signOut(token, SyncDestinationKind.GDRIVE, "cid") }.getOrThrow()
        assertEquals(listOf("GDRIVE:cid:unsaved-token"), revoked)
        assertTrue(token.all { it == '\u0000' })
        assertNull(s.config.value)
    }

    @Test
    fun `saving needs a client id and the secret is the refresh token kept encrypted under the destination id`() {
        val s = service()
        assertTrue(runBlocking { s.saveDestination(saved.copy(oauth = OAuthSettings(" "), id = "bad"), null) }.isFailure)
        runBlocking { s.saveDestination(saved, "refresh-9".toCharArray()) }.getOrThrow()
        assertEquals("refresh-9", secrets.map["d1"])
        assertFalse(SyncConfigCodec.encode(configs.saved!!).contains("refresh-9"))
    }

    @Test
    fun `the offline flag of the sign in seam matches the transport seam`() {
        assertEquals(SyncTransports.available, OAuthPlatform.available)
        val result = runBlocking { UnavailableSyncOAuth.signIn(SyncDestinationKind.GDRIVE, "x") }
        assertEquals(SyncFailure.UNAVAILABLE_BUILD, (result.exceptionOrNull() as SyncException).failure)
        assertNotEquals("", SyncCopy.NEEDS_CONNECT_BUILD)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SyncOAuthFormTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeOAuth : SyncOAuth {
        val calls = ArrayList<Pair<SyncDestinationKind, String>>()
        var next: Result<OAuthSignedIn> = Result.success(OAuthSignedIn("rt-token".toCharArray(), "me@example.org"))
        override suspend fun signIn(kind: SyncDestinationKind, clientId: String): Result<OAuthSignedIn> {
            calls += kind to clientId
            return next
        }
    }

    private fun show(service: SyncService, oauth: SyncOAuth) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SyncSettingsContent(service, emptyList(), {}, oauth = oauth)
            }
        }
    }

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun type(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performTextInput(text) }

    @Test
    fun `the google form explains the one-time setup and shows the exact redirect to register`() {
        show(FakeSyncService(), FakeOAuth())
        tap("sync-kind-gdrive")
        compose.onNodeWithText(SyncCopy.HOWTO_GOOGLE).assertExists()
        compose.onNodeWithText(SyncCopy.ONE_TIME_SETUP).assertExists()
        compose.onNodeWithText(OAuthRedirect.GOOGLE_URI).assertExists()
        compose.onNodeWithText(SyncCopy.GOOGLE_SCOPE_NOTE).assertExists()
        compose.onNodeWithText(SyncCopy.NOT_SIGNED_IN).assertExists()
    }

    @Test
    fun `the dropbox form names its own redirect and permissions`() {
        show(FakeSyncService(), FakeOAuth())
        tap("sync-kind-dropbox")
        compose.onNodeWithText(OAuthRedirect.DROPBOX_URI).assertExists()
        compose.onNodeWithText(SyncCopy.HOWTO_DROPBOX).assertExists()
        compose.onNodeWithText(SyncCopy.DROPBOX_SCOPE_NOTE).assertExists()
    }

    @Test
    fun `sign in without a client id asks for it and never opens the browser`() {
        val oauth = FakeOAuth()
        show(FakeSyncService(), oauth)
        tap("sync-kind-gdrive")
        tap("sync-signin")
        compose.onNodeWithText(SyncCopy.NEED_CLIENT_ID).assertExists()
        assertTrue(oauth.calls.isEmpty())
    }

    @Test
    fun `nothing signs in until the tap, then the account shows and sign out undoes it`() {
        val oauth = FakeOAuth()
        val service = FakeSyncService()
        show(service, oauth)
        tap("sync-kind-gdrive")
        type("sync-oauth-client", "123-abc.apps.googleusercontent.com")
        assertTrue("typing the id alone does not sign in", oauth.calls.isEmpty())
        tap("sync-signin")
        compose.waitForIdle()
        assertEquals(listOf(SyncDestinationKind.GDRIVE to "123-abc.apps.googleusercontent.com"), oauth.calls)
        compose.onNodeWithTag("sync-oauth-account").assertExists()
        compose.onNodeWithText(SyncCopy.signedInAs("me@example.org")).assertExists()
        compose.onNodeWithTag("sync-signin").assertDoesNotExist()
        tap("sync-signout")
        compose.waitForIdle()
        assertTrue(service.calls.any { it == "signout:unsaved=true" })
        compose.onNodeWithText(SyncCopy.NOT_SIGNED_IN).assertExists()
        compose.onNodeWithTag("sync-signin").assertExists()
    }

    @Test
    fun `a failed sign in says why and stays signed out`() {
        val oauth = FakeOAuth().apply { next = Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SIGN_IN_DENIED)) }
        show(FakeSyncService(), oauth)
        tap("sync-kind-dropbox")
        type("sync-oauth-client", "abc123def456ghi")
        tap("sync-signin")
        compose.waitForIdle()
        compose.onNodeWithText(SyncCopy.SIGN_IN_DENIED).assertExists()
        compose.onNodeWithText(SyncCopy.NOT_SIGNED_IN).assertExists()
    }

    @Test
    fun `send stays disabled until signed in`() {
        show(FakeSyncService(), FakeOAuth())
        tap("sync-kind-gdrive")
        type("sync-oauth-client", "123-abc.apps.googleusercontent.com")
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the offline kinds do not include the cloud accounts`() {
        show(FakeSyncService(supportedKinds = setOf(SyncDestinationKind.FOLDER)), FakeOAuth())
        compose.onNodeWithTag("sync-kind-gdrive").assertDoesNotExist()
        compose.onNodeWithTag("sync-kind-dropbox").assertDoesNotExist()
        compose.onNodeWithText(SyncCopy.NEEDS_CONNECT_BUILD).assertExists()
    }
}
