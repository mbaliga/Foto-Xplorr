package com.fotoxplorr.app.sync

import com.fotoxplorr.app.backup.BackupKeys
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for the audit of the sync package: each fails on the code as it was before its fix. */
class SyncAuditFixesTest {
    @get:Rule val temp = TemporaryFolder()
    private val servers = ArrayList<FakeHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private class MemorySecrets(var failPut: Throwable? = null) : SyncSecrets {
        val map = HashMap<String, String>()
        override fun get(id: String) = map[id]?.toCharArray()
        override fun put(id: String, value: CharArray) { failPut?.let { throw it }; map[id] = String(value); value.fill('\u0000') }
        override fun remove(id: String) { map.remove(id) }
    }

    private class MemoryConfig : SyncConfigStore {
        var saved: SyncDestinationConfig? = null
        override fun load() = saved
        override fun save(config: SyncDestinationConfig) { saved = config }
        override fun clear() { saved = null }
    }

    private val library = FakeLibrary()
    private val configs = MemoryConfig()
    private val secrets = MemorySecrets()
    private val manifests = HashMap<String, MemoryManifestStore>()
    private lateinit var dest: File
    private var assets: List<MediaAsset> = emptyList()
    private val runner = JobRunner(CoroutineScope(Dispatchers.Default))

    private fun asset(id: Long, size: Long = 100L + id) = MediaAsset(
        id = MediaId(id), contentUriString = "content://x/$id", displayName = "IMG_$id.jpg", mimeType = "image/jpeg", bucketName = "Camera",
        dateTakenMillis = 1_700_000_000_000L + id, dateModifiedSeconds = id, width = 1, height = 1, sizeBytes = size,
        relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private fun service(count: Int = 3, big: Boolean = false): DefaultSyncService {
        dest = temp.newFolder()
        assets = (1L..count.toLong()).map { asset(it) }
        assets.forEach { library.add(it.id.value, it.displayName, randomBytes(it.sizeBytes.toInt(), it.id.value), takenMillis = it.dateTakenMillis, modifiedSeconds = it.dateModifiedSeconds, folder = "DCIM/Camera") }
        if (big) {
            library.add(99, "big.jpg", randomBytes(30_000_000, 9), takenMillis = 1_800_000_000_000L)
            assets = assets + asset(99, 30_000_000)
        }
        return DefaultSyncService(
            configStore = configs, secrets = secrets,
            manifestStores = { manifests.getOrPut(it) { MemoryManifestStore() } },
            library = { assets }, fileSource = { _, _ -> library.source },
            providers = { _, _ -> SafFolderProvider(DirectoryFileSystem(dest)) },
            network = { SyncNetwork.UNMETERED }, runner = { runner },
            supportedKinds = SyncDestinationKind.entries.toSet(), io = Dispatchers.Default, retry = SyncRetryPolicy.NONE, sleep = {},
        )
    }

    private fun folderConfig(encrypt: Boolean = false) = SyncDestinationConfig(
        "d1", SyncDestinationKind.FOLDER, "Folder", folderTreeUri = "content://tree/x", selection = SyncSelection(allPhotos = true), encryptEnabled = encrypt,
    )

    private fun awaitEnd(s: DefaultSyncService): SyncRunState = runBlocking {
        withTimeout(60_000) {
            var st = s.state.value
            while (!(st is SyncRunState.Finished || st is SyncRunState.Failed || st is SyncRunState.Paused)) {
                kotlinx.coroutines.delay(20)
                st = s.state.value
            }
            st
        }
    }

    // ---- 1: the passphrase must survive the caller wiping its own buffer ----

    @Test
    fun `the caller wipes its passphrase right after start and the key is still made from the real one`() {
        val s = service()
        runBlocking { s.saveDestination(folderConfig(encrypt = true), null) }
        val typed = "twelve or more characters".toCharArray()
        assertEquals(SyncStartResult.Started, s.start(typed))
        typed.fill('\u0000') // what the settings screen does on the main thread, long before the job derives its key
        val end = awaitEnd(s)
        assertTrue(end.toString(), end is SyncRunState.Finished)
        val saved = configs.saved!!.encryption!!
        val again = BackupKeys.derive("twelve or more characters".toCharArray(), SyncEncryption.unhex(saved.saltHex), saved.iterations)
        assertEquals("the key check belongs to the typed passphrase, not to NULs", saved.keyCheckHex, SyncEncryption.keyCheck(again))
        again.close()
        val nuls = BackupKeys.derive(CharArray(25), SyncEncryption.unhex(saved.saltHex), saved.iterations)
        assertFalse(saved.keyCheckHex == SyncEncryption.keyCheck(nuls))
        nuls.close()
    }

    @Test
    fun `an empty or already wiped passphrase is not a passphrase`() {
        val s = service()
        runBlocking { s.saveDestination(folderConfig(encrypt = true), null) }
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.PASSPHRASE_NEEDED), s.start(CharArray(0)))
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.PASSPHRASE_NEEDED), s.start(CharArray(25)))
        assertEquals(0, dest.walkTopDown().count { it.isFile })
        assertNull(configs.saved!!.encryption)
    }

    // ---- 2: protection changes reach a run that is going ----

    @Test
    fun `a folder locked after the run started stops the files that come later`() {
        val s = service(count = 3, big = true)
        runBlocking { s.saveDestination(folderConfig(), null) }
        assertEquals(SyncStartResult.Started, s.start())
        runBlocking { withTimeout(30_000) { while (s.state.value !is SyncRunState.Running) kotlinx.coroutines.delay(2) } }
        assets = emptyList() // the feeder hands over the new browsable list: everything is locked now
        val end = awaitEnd(s)
        val tally = (end as? SyncRunState.Finished)?.tally ?: error(end.toString())
        assertTrue("at most the file already in flight went: $tally", tally.uploaded <= 1)
        assertTrue(tally.skipped >= 3)
    }

    @Test
    fun `the holder takes the library back when its feeder leaves, and sends nothing meanwhile`() {
        var seen: () -> List<MediaAsset> = { error("not built") }
        val holder = com.fotoxplorr.app.gallery.SyncServiceHolder { browsable, _ -> seen = browsable; FakeSyncService() }
        holder.obtain()
        assertTrue("fail closed before anything was fed", seen().isEmpty())
        holder.feed(listOf(asset(1)), null)
        assertEquals(1, seen().size)
        holder.detach()
        assertTrue(seen().isEmpty())
    }

    // ---- 15: forgetting while a run is going ----

    @Test
    fun `a run that outlives forget cannot bring the state or the settings back`() {
        val s = service(big = true)
        runBlocking { s.saveDestination(folderConfig(encrypt = true), null) }
        s.start("twelve or more characters".toCharArray())
        s.forgetDestination()
        runBlocking { kotlinx.coroutines.delay(2_500) }
        assertEquals(SyncRunState.Idle, s.state.value)
        assertNull(s.config.value)
        assertNull("the settings file was not written again by the dying run", configs.saved)
    }

    // ---- 13: a keystore failure is a sentence, not a crash ----

    @Test
    fun `a keystore failure while saving a password is reported`() {
        val s = service()
        secrets.failPut = java.security.GeneralSecurityException("keystore")
        val dav = SyncDestinationConfig("w", SyncDestinationKind.WEBDAV, "n", webDav = WebDavSettings("https://h/dav/", "me"))
        val result = runBlocking { s.saveDestination(dav, "pw".toCharArray()) }
        assertEquals(SyncCopy.SECRET_STORE_FAILED, result.exceptionOrNull()!!.message)
        secrets.failPut = java.security.ProviderException("x")
        assertEquals(SyncCopy.SECRET_STORE_FAILED, runBlocking { s.saveDestination(dav, "pw".toCharArray()) }.exceptionOrNull()!!.message)
    }

    // ---- 3: junk answers do not make a file "done" (over WebDAV and S3) ----

    @Test
    fun `a proxy that answers 200 with a web page is not a confirmation over WebDAV`() {
        val server = FakeHttpServer { ex, req -> when (req.method) {
            "PUT" -> servers.last().reply(ex, 201)
            "PROPFIND" -> servers.last().reply(ex, 200, "<html>captive portal</html>".toByteArray())
            else -> servers.last().reply(ex, 201)
        } }
        servers += server
        val lib = FakeLibrary()
        val c = lib.add(1, "a.jpg", randomBytes(100))
        val store = MemoryManifestStore()
        val provider = WebDavProvider(WebDavSettings(server.base + "/dav/", "me", true), "pw".toCharArray(), SocketHttpTransport())
        val state = runBlocking { SyncEngine(provider, lib.source, store, { true }, retry = SyncRetryPolicy.NONE, sleep = {}).run(lib.candidates, null) {} }.state
        assertEquals(1, (state as SyncRunState.Finished).tally.failed)
        assertNull(store.load().doneFor(c.key))
    }

    @Test
    fun `an S3 head that cannot confirm is not a confirmation either`() {
        val server = FakeHttpServer { ex, req -> servers.last().reply(ex, if (req.method == "HEAD") 404 else 200) }
        servers += server
        val lib = FakeLibrary()
        val c = lib.add(1, "a.jpg", randomBytes(100))
        val store = MemoryManifestStore()
        val provider = S3CompatibleProvider(S3Settings(server.base, "r", "bucket-1", "AK", allowPlainHttpOnLocal = true), "k".toCharArray(), SocketHttpTransport())
        val state = runBlocking { SyncEngine(provider, lib.source, store, { true }, retry = SyncRetryPolicy.NONE, sleep = {}).run(lib.candidates, null) {} }.state
        assertEquals(1, (state as SyncRunState.Finished).tally.failed)
        assertNull(store.load().doneFor(c.key))
    }

    // ---- 6: S3 never overwrites ----

    private fun s3(server: FakeHttpServer, tuning: S3Tuning = S3Tuning()) =
        S3CompatibleProvider(S3Settings(server.base, "r", "bucket-1", "AK", allowPlainHttpOnLocal = true), "k".toCharArray(), SocketHttpTransport(), tuning)

    @Test
    fun `an S3 put that must not replace sends if-none-match and a 412 is a refusal`() {
        val server = FakeHttpServer { ex, req -> servers.last().reply(ex, if (req.headers["if-none-match"] == "*") 412 else 200) }
        servers += server
        val p = s3(server)
        val e = runCatching { p.put("a.jpg", "x".byteInputStream(), 1, false, Cancellation.NEVER) {} }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.REJECTED, e.failure)
        assertEquals("*", server.seen.first { it.method == "PUT" }.headers["if-none-match"])
        assertTrue(server.seen.first { it.method == "PUT" }.headers["authorization"]!!.contains("if-none-match"))
    }

    @Test
    fun `replacing our own partial sends no condition`() {
        val server = FakeHttpServer { ex, _ -> servers.last().reply(ex, 200) }
        servers += server
        runCatching { s3(server).put("a.jpg", "x".byteInputStream(), 1, true, Cancellation.NEVER) {} }
        assertNull(server.seen.first { it.method == "PUT" }.headers["if-none-match"])
    }

    @Test
    fun `a service that does not know the condition is asked again without it`() {
        val server = FakeHttpServer { ex, req -> servers.last().reply(ex, if (req.headers.containsKey("if-none-match")) 501 else 200) }
        servers += server
        val p = s3(server)
        p.put("a.jpg", "x".byteInputStream(), 1, false, Cancellation.NEVER) {}
        p.put("b.jpg", "x".byteInputStream(), 1, false, Cancellation.NEVER) {}
        val puts = server.seen.filter { it.method == "PUT" }
        assertEquals("first tried with, then without; the second file goes straight to without", listOf(true, false, false), puts.map { it.headers.containsKey("if-none-match") })
    }

    @Test
    fun `a 403 on head of a missing key without list rights reads as not there`() {
        val server = FakeHttpServer { ex, _ -> servers.last().reply(ex, 403) }
        servers += server
        assertNull(s3(server).stat("a.jpg"))
    }

    // ---- 10, 11, 12 ----

    @Test
    fun `a forged redirect does not end the pending sign-in`() {
        val waiting = OAuthRendezvous.begin("the-real-state")
        assertFalse(OAuthRendezvous.deliver("com.fotoxplorr.app.oauth:/cb?code=evil&state=forged"))
        assertFalse(OAuthRendezvous.deliver("com.fotoxplorr.app.oauth:/cb?code=evil"))
        assertFalse(waiting.isCompleted)
        assertTrue(OAuthRendezvous.deliver("com.fotoxplorr.app.oauth:/cb?code=good&state=the-real-state"))
        assertTrue(waiting.isCompleted)
        assertEquals("a b", OAuthRendezvous.stateOf("https://x/auth?state=a%20b&y=1"))
    }

    @Test
    fun `octal looking octets and names are not trusted as private numbers`() {
        assertFalse(HostRules.isLocalHost("010.0.0.1"))
        assertFalse(HostRules.isLocalHost("192.168.01.5"))
        assertTrue(HostRules.isLocalHost("10.0.0.1"))
        assertTrue(HostRules.isLocalHost("192.168.1.5"))
    }

    @Test
    fun `a bad location header is a refusal, not a crash`() {
        for (bad in listOf("http://[bad host/x", "ht tp://x", "http:///nohost")) {
            val t = HttpTransport { _, _ -> HttpResponse(302, mapOf("location" to bad), ByteArray(0).inputStream()) }
            val e = runCatching { HttpCaller(t).call(HttpRequest("GET", "https://h/a")) }.exceptionOrNull()
            assertTrue("$bad -> $e", e is SyncException)
        }
    }

    @Test
    fun `a percent escape at the very end of a path is decoded`() {
        assertEquals("/a/A", WebDavXml.decodePath("/a/%41"))
        assertEquals("/a/%4", WebDavXml.decodePath("/a/%4"))
    }

    // ---- 18 and copy ----

    @Test
    fun `lookalikes are found by signature after new records too`() {
        val m = SyncManifest()
        m.recordDone(SyncDone("a", 10, 5, "Fotoz/a", 10, "a".repeat(64), 1))
        assertEquals(1, m.lookalikesOf("b", 10, 5).size)
        m.recordDone(SyncDone("c", 10, 5, "Fotoz/c", 10, "b".repeat(64), 1))
        assertEquals(2, m.lookalikesOf("b", 10, 5).size)
        assertEquals(1, m.lookalikesOf("a", 10, 5).size)
    }

    @Test
    fun `the privacy line no longer claims a password stays on the phone, and unencrypted copies are said to lose location`() {
        assertFalse(SyncCopy.PRIVACY.contains("Passwords stay"))
        assertTrue(SyncCopy.PRIVACY.contains("Your password goes only to your server"))
        assertTrue(SyncCopy.LOCATION_NOTE.contains("not encrypted"))
    }
}
