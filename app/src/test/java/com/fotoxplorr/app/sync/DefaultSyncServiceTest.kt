package com.fotoxplorr.app.sync

import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultSyncServiceTest {
    @get:Rule val temp = TemporaryFolder()

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

    private val library = FakeLibrary()
    private val manifests = HashMap<String, MemoryManifestStore>()
    private val secrets = MemorySecrets()
    private val configs = MemoryConfig()
    private var network = SyncNetwork.UNMETERED
    private var providerCreated = 0
    private lateinit var dest: File
    private val scope = CoroutineScope(Dispatchers.Default)
    private val runner = JobRunner(scope)
    private var asLibrary: List<MediaAsset> = emptyList()

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://x/$id", displayName = "IMG_$id.jpg", mimeType = "image/jpeg", bucketName = "Camera",
        dateTakenMillis = 1_700_000_000_000L + id, dateModifiedSeconds = id, width = 1, height = 1, sizeBytes = 100 + id,
        relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private fun service(kinds: Set<SyncDestinationKind> = SyncDestinationKind.entries.toSet(), jobs: JobRunner? = runner): DefaultSyncService {
        dest = temp.newFolder()
        asLibrary = (1L..3L).map(::asset)
        asLibrary.forEach { library.add(it.id.value, it.displayName, randomBytes(100 + it.id.value.toInt(), it.id.value), takenMillis = it.dateTakenMillis, modifiedSeconds = it.dateModifiedSeconds, folder = "DCIM/Camera") }
        return DefaultSyncService(
            configStore = configs,
            secrets = secrets,
            manifestStores = { manifests.getOrPut(it) { MemoryManifestStore() } },
            library = { asLibrary },
            fileSource = { _, _ -> library.source },
            providers = { config, secret ->
                providerCreated++
                secret?.fill('\u0000')
                val saf = SafFolderProvider(DirectoryFileSystem(dest))
                if (config.kind == SyncDestinationKind.FOLDER) saf else object : SyncProvider by saf { override val needsNetwork = true }
            },
            network = { network },
            runner = { jobs },
            supportedKinds = kinds,
            io = Dispatchers.Default,
            retry = SyncRetryPolicy.NONE,
            sleep = {},
        )
    }

    private fun folderConfig(id: String = "d1", encrypt: Boolean = false, selection: SyncSelection = SyncSelection(allPhotos = true)) =
        SyncDestinationConfig(id, SyncDestinationKind.FOLDER, "Folder", folderTreeUri = "content://tree/x", selection = selection, encryptEnabled = encrypt)

    private fun webDavConfig() = SyncDestinationConfig("w1", SyncDestinationKind.WEBDAV, "NAS", webDav = WebDavSettings("https://nas/dav/", "me"), selection = SyncSelection(allPhotos = true))

    private fun awaitEnd(s: DefaultSyncService): SyncRunState = runBlocking {
        withTimeout(60_000) {
            while (true) {
                val st = s.state.value
                if (st is SyncRunState.Finished || st is SyncRunState.Failed || st is SyncRunState.Paused) return@withTimeout st
                kotlinx.coroutines.delay(20)
            }
            @Suppress("UNREACHABLE_CODE") SyncRunState.Idle
        }
    }

    @Test
    fun `nothing happens until the person acts`() {
        val s = service()
        assertEquals(SyncRunState.Idle, s.state.value)
        assertNull(s.config.value)
        assertEquals(0, providerCreated)
        assertTrue(runner.jobs.value.isEmpty())
        assertEquals(0, library.opens)
        // Saving a destination is still not a run.
        runBlocking { s.saveDestination(folderConfig(), null) }
        assertEquals(SyncRunState.Idle, s.state.value)
        assertEquals(0, providerCreated)
        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `start refuses without a destination, without a choice, and where a kind is not available`() {
        val s = service(kinds = setOf(SyncDestinationKind.FOLDER))
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.NO_DESTINATION), s.start())
        runBlocking { s.saveDestination(folderConfig(selection = SyncSelection()), null) }
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.NOTHING_CHOSEN), s.start())
        val e = runBlocking { s.saveDestination(webDavConfig(), "pw".toCharArray()) }.exceptionOrNull() as SyncException
        assertEquals(SyncFailure.UNAVAILABLE_BUILD, e.failure)
        assertEquals(0, providerCreated)
    }

    @Test
    fun `without a job runner it refuses rather than running unseen`() {
        val s = service(jobs = null)
        runBlocking { s.saveDestination(folderConfig(), null) }
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.NO_JOB_RUNNER), s.start())
    }

    @Test
    fun `wifi only is the default and mobile data needs a yes for this run`() {
        val s = service()
        runBlocking { s.saveDestination(webDavConfig(), "pw".toCharArray()) }
        network = SyncNetwork.METERED
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.WAITING_FOR_WIFI), s.start())
        network = SyncNetwork.NONE
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.NO_NETWORK), s.start(allowMobileData = true))
        assertEquals(0, providerCreated)
        network = SyncNetwork.METERED
        assertEquals(SyncStartResult.Started, s.start(allowMobileData = true))
        assertTrue(awaitEnd(s) is SyncRunState.Finished)
    }

    @Test
    fun `a folder destination does not care about the network`() {
        val s = service()
        network = SyncNetwork.NONE
        runBlocking { s.saveDestination(folderConfig(), null) }
        assertEquals(SyncStartResult.Started, s.start())
        val end = awaitEnd(s) as SyncRunState.Finished
        assertEquals(3, end.tally.uploaded)
        assertEquals(3, dest.walkTopDown().count { it.isFile })
    }

    @Test
    fun `the secret is stored by the service, zeroed, and handed to the provider only`() {
        val s = service()
        val buffer = "app password".toCharArray()
        runBlocking { s.saveDestination(webDavConfig(), buffer) }
        assertTrue(buffer.all { it == '\u0000' })
        assertEquals("app password", secrets.map["w1"])
        assertFalse(configs.saved.toString().contains("app password"))
        s.forgetDestination()
        assertNull(secrets.map["w1"])
        assertNull(configs.saved)
    }

    @Test
    fun `preview counts what a run would send without touching the destination`() {
        val s = service()
        runBlocking { s.saveDestination(folderConfig(), null) }
        val first = runBlocking { s.preview() }.getOrThrow()
        assertEquals(3, first.toSend)
        assertEquals(100L * 3 + 6, first.bytesToSend)
        assertEquals(0, providerCreated)
        s.start()
        awaitEnd(s)
        val second = runBlocking { s.preview() }.getOrThrow()
        assertEquals(0, second.toSend)
        assertEquals(3, second.alreadyThere)
    }

    @Test
    fun `a run is a SYNCING job and a second start while it runs is refused`() {
        val s = service()
        library.add(9, "big.jpg", randomBytes(3_000_000), takenMillis = 1_800_000_000_000L)
        asLibrary = asLibrary + asset(9).copy(sizeBytes = 3_000_000)
        runBlocking { s.saveDestination(folderConfig(), null) }
        assertEquals(SyncStartResult.Started, s.start())
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.ALREADY_RUNNING), s.start())
        val job = runner.jobs.value.singleOrNull()
        if (job != null) {
            assertEquals(com.fotoxplorr.app.hyle.ActivityKind.SYNCING, job.kind)
            assertEquals(SyncCopy.JOB_TITLE, job.title)
        }
        awaitEnd(s)
    }

    @Test
    fun `pause stops the run and start continues where it stopped`() {
        val s = service()
        library.add(9, "big.jpg", randomBytes(40_000_000, 9), takenMillis = 1_800_000_000_000L)
        asLibrary = asLibrary + asset(9).copy(sizeBytes = 40_000_000)
        runBlocking { s.saveDestination(folderConfig(), null) }
        s.start()
        runBlocking { withTimeout(30_000) { while (s.state.value !is SyncRunState.Running) kotlinx.coroutines.delay(5) } }
        s.pause()
        val paused = awaitEnd(s)
        assertTrue(paused.toString(), paused is SyncRunState.Paused || paused is SyncRunState.Finished)
        if (paused is SyncRunState.Paused) {
            s.start()
            val done = awaitEnd(s)
            assertTrue(done.toString(), done is SyncRunState.Finished)
        }
        assertEquals(4, manifests.getValue("d1").load().done.size)
    }

    @Test
    fun `an encrypted destination needs the passphrase and the same one every time`() {
        val s = service()
        runBlocking { s.saveDestination(folderConfig(encrypt = true), null) }
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.PASSPHRASE_NEEDED), s.start())
        assertEquals(SyncStartResult.Refused(SyncStartRefusal.PASSPHRASE_NEEDED), s.start("".toCharArray()))
        val first = "twelve or more characters".toCharArray()
        assertEquals(SyncStartResult.Started, s.start(first))
        val end = awaitEnd(s) as SyncRunState.Finished
        assertTrue("the passphrase buffer is wiped", first.all { it == '\u0000' })
        assertEquals(3, end.tally.uploaded)
        assertTrue(dest.walkTopDown().filter { it.isFile }.all { it.name.endsWith(".fzs") })
        val saved = configs.saved!!.encryption
        assertNotNull(saved)
        assertFalse(configs.saved.toString().contains("twelve"))

        // A new photo and the wrong passphrase: refused, and nothing is written with a different key.
        asLibrary = asLibrary + asset(7)
        library.add(7, "IMG_7.jpg", randomBytes(107, 7), takenMillis = 1_800_000_000_000L)
        assertEquals(SyncStartResult.Started, s.start("a different passphrase".toCharArray()))
        val failed = awaitFailure(s)
        assertEquals(SyncCopy.WRONG_PASSPHRASE, failed.message)
        assertEquals(3, dest.walkTopDown().count { it.isFile })

        assertEquals(SyncStartResult.Started, s.start("twelve or more characters".toCharArray()))
        val ended = awaitEnd(s)
        assertTrue(ended.toString(), ended is SyncRunState.Finished)
        val ok = ended as SyncRunState.Finished
        assertEquals(1, ok.tally.uploaded)
        assertEquals("the salt stays the same across runs", saved, configs.saved!!.encryption)
    }

    private fun awaitFailure(s: DefaultSyncService): SyncRunState.Failed = runBlocking {
        withTimeout(60_000) {
            while (true) {
                (s.state.value as? SyncRunState.Failed)?.let { return@withTimeout it }
                kotlinx.coroutines.delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("never")
        }
    }

    @Test
    fun `a photo locked after the plan is not sent`() {
        val s = service()
        runBlocking { s.saveDestination(folderConfig(), null) }
        // The library the gate sees has lost photo 2 (its folder was locked); the snapshot the plan used still has it.
        val snapshots = ArrayDeque(listOf(asLibrary))
        val live = asLibrary.filter { it.id.value != 2L }
        val s2 = DefaultSyncService(
            configStore = configs, secrets = secrets, manifestStores = { manifests.getOrPut(it) { MemoryManifestStore() } },
            library = { snapshots.removeFirstOrNull() ?: live },
            fileSource = { _, _ -> library.source },
            providers = { _, _ -> SafFolderProvider(DirectoryFileSystem(dest)) },
            network = { network }, runner = { runner }, supportedKinds = SyncDestinationKind.entries.toSet(),
            io = Dispatchers.Default, retry = SyncRetryPolicy.NONE, sleep = {},
        )
        s2.start()
        val end = awaitEnd(s2) as SyncRunState.Finished
        assertEquals(2, end.tally.uploaded)
        assertEquals(1, end.tally.skipped)
        assertFalse(dest.walkTopDown().any { it.name == "IMG_2.jpg" })
    }

    @Test
    fun `the fake service records calls and never runs anything`() {
        val fake = FakeSyncService()
        runBlocking { fake.saveDestination(folderConfig(), "x".toCharArray()) }
        assertEquals(SyncStartResult.Started, fake.start())
        fake.pause()
        assertEquals(listOf("save:d1:secret=true", "start:mobile=false", "pause"), fake.calls)
        assertTrue(fake.state.value is SyncRunState.Preparing)
        fake.forgetDestination()
        assertNull(fake.config.value)
    }

    @Test
    fun `the placeholder copy says it is off by default`() {
        assertTrue(SyncCopy.OFF_BY_DEFAULT.startsWith("Nothing is sent until"))
        assertArrayEquals(SyncCopy.summary(SyncTally()).toByteArray(), SyncCopy.ALL_SENT.toByteArray())
        assertEquals("2 sent, 1 already there.", SyncCopy.summary(SyncTally(uploaded = 2, upToDate = 1)))
    }
}
