package com.fotoxplorr.app.sync

import com.fotoxplorr.app.backup.BackupKeys
import com.fotoxplorr.app.backup.BackupFormat
import com.fotoxplorr.app.backup.BackupKind
import com.fotoxplorr.app.backup.BackupStreams
import java.io.File
import java.io.InputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private val library = FakeLibrary()
    private val store = MemoryManifestStore()
    private val sleeps = ArrayList<Long>()

    private fun fs(): DirectoryFileSystem {
        root = temp.newFolder()
        return DirectoryFileSystem(root)
    }

    private fun engine(
        provider: SyncProvider,
        gate: UploadGate = UploadGate { true },
        encryption: SyncEncryption? = null,
        retry: SyncRetryPolicy = SyncRetryPolicy(maxAttempts = 3, baseMillis = 100, maxMillis = 1_000),
        betweenFiles: () -> Unit = {},
    ) = SyncEngine(provider, library.source, store, gate, encryption, retry, Random(1), { 1234L }, { sleeps += it }, betweenFiles = betweenFiles)

    private fun run(engine: SyncEngine, cap: Long? = null, onEvent: (SyncRunEvent) -> Unit = {}) =
        runBlocking { engine.run(library.candidates.toList(), cap, onEvent) }.state

    private fun remoteFiles(): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }

    @After
    fun noop() = Unit

    @Test
    fun `new files go up under the layout and are recorded with a hash`() {
        val provider = SafFolderProvider(fs())
        val a = library.add(1, "IMG_1.jpg", randomBytes(1000, 1))
        library.add(2, "IMG_2.jpg", randomBytes(2000, 2))
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(2, state.tally.uploaded)
        assertEquals(3000, state.tally.uploadedBytes)
        val files = remoteFiles()
        assertEquals(2, files.size)
        val path = RemoteLayout.pathFor(a)
        assertArrayEquals(library.contentOf(1), files[path])
        val done = store.load().doneFor(a.key)!!
        assertEquals(path, done.remotePath)
        assertEquals(library.contentOf(1).sha256Hex(), done.sha256)
        assertEquals(0, store.load().begun.size)
    }

    @Test
    fun `a second run sends nothing and does not even open the files`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "a.jpg", randomBytes(500))
        run(engine(provider))
        val opensBefore = library.opens
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(0, state.tally.uploaded)
        assertEquals(1, state.tally.upToDate)
        assertEquals(opensBefore, library.opens)
    }

    @Test
    fun `an edited photo is sent as a new file beside the old copy and nothing is deleted`() {
        val provider = SafFolderProvider(fs())
        val a = library.add(1, "a.jpg", randomBytes(500, 1))
        run(engine(provider))
        val first = RemoteLayout.pathFor(a)
        library.change(1, randomBytes(600, 2))
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        val files = remoteFiles()
        assertEquals(2, files.size)
        assertEquals(500, files.getValue(first).size)
        assertTrue(files.keys.any { it.endsWith("a (2).jpg") })
    }

    @Test
    fun `nothing local or remote is ever deleted when the library shrinks`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "a.jpg", randomBytes(100))
        library.add(2, "b.jpg", randomBytes(100))
        run(engine(provider))
        library.candidates.removeAt(0)
        run(engine(provider))
        assertEquals(2, remoteFiles().size)
    }

    @Test
    fun `the gate is asked again at upload time and a refused file is never opened`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "keep.jpg", randomBytes(100))
        library.add(2, "locked.jpg", randomBytes(100))
        val asked = ArrayList<Long>()
        val state = run(engine(provider, gate = { asked += it.mediaId; it.mediaId != 2L })) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        assertEquals(1, state.tally.skipped)
        assertEquals(setOf(1L, 2L), asked.toSet())
        assertEquals(1, library.opens)
        assertEquals(1, remoteFiles().size)
        assertNull(store.load().doneFor(library.candidates[1].key))
    }

    @Test
    fun `a file that vanished is skipped, not failed`() {
        val provider = SafFolderProvider(fs())
        library.candidates += SyncCandidate(99, "gone.jpg", "DCIM/", "image/jpeg", 10, 1_700_000_000_000L, 5, "dcim", false)
        library.add(1, "a.jpg", randomBytes(10))
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        assertEquals(1, state.tally.skipped)
    }

    @Test
    fun `the byte cap stops a run and the next run carries on`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "a.jpg", randomBytes(400, 1))
        library.add(2, "b.jpg", randomBytes(400, 2))
        library.add(3, "c.jpg", randomBytes(400, 3))
        val first = run(engine(provider), cap = 900) as SyncRunState.Finished
        assertEquals(2, first.tally.uploaded)
        assertEquals(1, first.tally.deferredByCap)
        val second = run(engine(provider), cap = 900) as SyncRunState.Finished
        assertEquals(1, second.tally.uploaded)
        assertEquals(2, second.tally.upToDate)
        assertEquals(3, remoteFiles().size)
    }

    @Test
    fun `stopping in the middle of a file leaves a begun line and no done line, and the next run finishes it in place`() {
        val filesystem = fs()
        val provider = SafFolderProvider(filesystem)
        val a = library.add(1, "big.jpg", randomBytes(600_000, 1))
        library.add(2, "small.jpg", randomBytes(100, 2), takenMillis = 1L)
        runBlocking {
            lateinit var job: Job
            job = CoroutineScope(Dispatchers.Default).launch {
                engine(provider).run(library.candidates.toList(), null) { if (it is SyncRunEvent.Bytes && it.inCurrentFile > 50_000) job.cancel() }
            }
            job.join()
        }
        val mid = store.load()
        assertNull(mid.doneFor(a.key))
        assertNotNull(mid.begunFor(a.key))
        val path = mid.begunFor(a.key)!!.remotePath
        assertTrue("a partial copy exists", File(root, path).length() < 600_000)
        assertEquals("the cancelled run did not go on to the next file", 0, mid.done.size)

        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(2, state.tally.uploaded)
        val after = store.load()
        assertEquals(path, after.doneFor(a.key)!!.remotePath)
        assertArrayEquals(library.contentOf(1), File(root, path).readBytes())
        assertEquals(2, remoteFiles().size)
        assertEquals(0, after.begun.size)
    }

    @Test
    fun `the run reports paused when cancelled`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "big.jpg", randomBytes(600_000, 1))
        library.add(2, "b.jpg", randomBytes(100, 2))
        val events = ArrayList<SyncRunEvent>()
        runBlocking {
            lateinit var job: Job
            job = CoroutineScope(Dispatchers.Default).launch {
                engine(provider).run(library.candidates.toList(), null) { events += it; if (it is SyncRunEvent.Bytes) job.cancel() }
            }
            job.join()
        }
        assertEquals(SyncRunEvent.Cancelled, events.last())
        assertTrue(events.none { it is SyncRunEvent.Completed })
    }

    // ---- failures ----

    private class FlakyProvider(private val inner: SyncProvider, var failPuts: Int, val failure: SyncFailure) : SyncProvider by inner {
        var puts = 0
        override fun put(path: String, input: InputStream, length: Long, overwrite: Boolean, cancel: Cancellation, onBytes: (Long) -> Unit): RemoteEntry? {
            puts++
            if (failPuts > 0) {
                failPuts--
                throw SyncException(failure, "boom")
            }
            return inner.put(path, input, length, overwrite, cancel, onBytes)
        }
    }

    @Test
    fun `transient trouble is retried with jittered waits and then succeeds`() {
        val flaky = FlakyProvider(SafFolderProvider(fs()), failPuts = 2, failure = SyncFailure.TRANSIENT)
        library.add(1, "a.jpg", randomBytes(100))
        val state = run(engine(flaky)) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        assertEquals(3, flaky.puts)
        assertEquals(2, sleeps.size)
        assertTrue(sleeps[0] in 50..100)
        assertTrue(sleeps[1] in 100..200)
        assertEquals("the file was opened afresh for every attempt", 3, library.opens)
    }

    @Test
    fun `transient trouble that outlasts the retries stops the run with the file still begun`() {
        val flaky = FlakyProvider(SafFolderProvider(fs()), failPuts = 99, failure = SyncFailure.TRANSIENT)
        val a = library.add(1, "a.jpg", randomBytes(100))
        library.add(2, "b.jpg", randomBytes(100), takenMillis = 5L)
        val state = run(engine(flaky)) as SyncRunState.Failed
        assertEquals(SyncFailure.TRANSIENT, state.failure)
        assertEquals(3, flaky.puts)
        assertNotNull(store.load().begunFor(a.key))
        assertNull(store.load().doneFor(a.key))
    }

    @Test
    fun `login trouble and a full destination stop the run at once without retrying`() {
        for (failure in listOf(SyncFailure.AUTH, SyncFailure.NO_SPACE, SyncFailure.UNSAFE, SyncFailure.DESTINATION_GONE)) {
            library.candidates.clear()
            library.add(1, "a.jpg", randomBytes(100))
            library.add(2, "b.jpg", randomBytes(100))
            val flaky = FlakyProvider(SafFolderProvider(fs()), failPuts = 99, failure = failure)
            val state = run(engine(flaky)) as SyncRunState.Failed
            assertEquals(failure, state.failure)
            assertEquals("$failure: no second file tried", 1, flaky.puts)
        }
    }

    @Test
    fun `a file the destination refuses is counted and the run goes on`() {
        val flaky = FlakyProvider(SafFolderProvider(fs()), failPuts = 1, failure = SyncFailure.REJECTED)
        library.add(1, "newest.jpg", randomBytes(100), takenMillis = 9_000_000_000_000L)
        library.add(2, "older.jpg", randomBytes(100), takenMillis = 1L)
        val state = run(engine(flaky)) as SyncRunState.Finished
        assertEquals(1, state.tally.failed)
        assertEquals(1, state.tally.uploaded)
    }

    @Test
    fun `a card that fails mid write is not recorded as done`() {
        val filesystem = fs()
        filesystem.failWritesAfterBytes = 10
        val a = library.add(1, "a.jpg", randomBytes(5000))
        val state = run(engine(SafFolderProvider(filesystem), retry = SyncRetryPolicy.NONE))
        assertTrue(state is SyncRunState.Failed)
        assertNull(store.load().doneFor(a.key))
    }

    @Test
    fun `the between-files check can stop a run when Wi-Fi goes away`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "a.jpg", randomBytes(100))
        library.add(2, "b.jpg", randomBytes(100), takenMillis = 1L)
        var calls = 0
        val state = run(engine(provider, betweenFiles = { if (++calls == 2) throw SyncException(SyncFailure.TRANSIENT, "no wifi") }))
        assertTrue(state is SyncRunState.Failed)
        assertEquals(1, store.load().done.size)
    }

    // ---- adoption, names, twins ----

    @Test
    fun `a file already at the destination with the right size is adopted, not sent again`() {
        val filesystem = fs()
        val provider = SafFolderProvider(filesystem)
        val a = library.add(1, "a.jpg", randomBytes(700))
        run(engine(provider))
        store.text = "" // the manifest is lost (reinstall)
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(0, state.tally.uploaded)
        assertEquals(1, state.tally.adopted)
        assertEquals(1, remoteFiles().size)
        assertEquals(RemoteLayout.pathFor(a), store.load().doneFor(a.key)!!.remotePath)
    }

    @Test
    fun `someone else's file with our name is never overwritten`() {
        val filesystem = fs()
        val a = library.add(1, "a.jpg", randomBytes(700))
        val path = RemoteLayout.pathFor(a)
        File(root, path).parentFile!!.mkdirs()
        File(root, path).writeBytes(ByteArray(5) { 9 })
        val state = run(engine(SafFolderProvider(filesystem))) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        assertArrayEquals(ByteArray(5) { 9 }, File(root, path).readBytes())
        assertTrue(store.load().doneFor(a.key)!!.remotePath.endsWith("a (2).jpg"))
    }

    @Test
    fun `a renamed twin is recognised by its hash and not uploaded again`() {
        val provider = SafFolderProvider(fs())
        val original = library.add(1, "old.jpg", randomBytes(900), modifiedSeconds = 77)
        run(engine(provider))
        // Same bytes, same size and modified time, found under a new name.
        library.candidates.clear()
        library.candidates += original.copy(displayName = "new.jpg")
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(0, state.tally.uploaded)
        assertEquals(1, state.tally.adopted)
        assertEquals(1, remoteFiles().size)
    }

    @Test
    fun `a lookalike with different bytes is uploaded`() {
        val provider = SafFolderProvider(fs())
        val original = library.add(1, "old.jpg", randomBytes(900, 1), modifiedSeconds = 77)
        run(engine(provider))
        library.candidates.clear()
        library.add(1, "new.jpg", randomBytes(900, 2), takenMillis = original.dateTakenMillis, modifiedSeconds = 77)
        val state = run(engine(provider)) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        assertEquals(2, remoteFiles().size)
    }

    // ---- encryption ----

    private fun keys() = BackupKeys.newSet("correct horse battery staple".toCharArray(), BackupFormat.MIN_ITERATIONS)

    @Test
    fun `encrypted files carry the suffix, have the predicted size and open with the passphrase`() {
        val provider = SafFolderProvider(fs())
        val keys = keys()
        val encryption = SyncEncryption(keys)
        val data = randomBytes(2_500_000, 4) // more than two 1 MiB segments
        val a = library.add(1, "secret.jpg", data)
        val state = run(engine(provider, encryption = encryption)) as SyncRunState.Finished
        assertEquals(1, state.tally.uploaded)
        val path = RemoteLayout.pathFor(a, SyncEncryption.SUFFIX)
        assertTrue(path.endsWith("secret.jpg.fzs"))
        val stored = File(root, path).readBytes()
        assertEquals(encryption.remoteSize(data.size.toLong()), stored.size.toLong())
        assertFalse("no plaintext run of the photo in the stored file", String(stored, Charsets.ISO_8859_1).contains(String(data.copyOfRange(1000, 1064), Charsets.ISO_8859_1)))
        // Another device: only the passphrase, through the header's own salt and iteration count.
        val header = BackupStreams.readHeader(stored.inputStream().also { })
        assertEquals(BackupKind.SYNC_FILE, header.kind)
        val reopened = BackupKeys.derive("correct horse battery staple".toCharArray(), header.setSalt, header.iterations)
        val plain = BackupStreams.open(stored.inputStream(), reopened, BackupKind.SYNC_FILE).plaintext.readBytes()
        assertArrayEquals(data, plain)
        assertEquals(encryption.remoteSize(data.size.toLong()), store.load().doneFor(a.key)!!.remoteSize)
        // The hash recorded is of the plain photo, so a rename is still recognised.
        assertEquals(data.sha256Hex(), store.load().doneFor(a.key)!!.sha256)
    }

    @Test
    fun `a sync file is refused where a backup pack is expected`() {
        val keys = keys()
        val sealed = java.io.ByteArrayOutputStream().also { sink ->
            SyncEncryption(keys).wrap(ByteArray(10).inputStream()).use { it.copyTo(sink) }
        }.toByteArray()
        val failure = runCatching { BackupStreams.open(sealed.inputStream(), keys, BackupKind.PACK) }.exceptionOrNull()
        assertTrue(failure is com.fotoxplorr.app.backup.BackupException.UnexpectedKind)
    }

    @Test
    fun `an empty file and an exact segment multiple encrypt and decrypt`() {
        val keys = keys()
        val encryption = SyncEncryption(keys, segmentSize = 4096)
        for (size in listOf(0, 1, 4095, 4096, 4097, 8192, 20_000)) {
            val data = randomBytes(size, size.toLong())
            val sealed = encryption.wrap(data.inputStream()).use { it.readBytes() }
            assertEquals("size $size", encryption.remoteSize(size.toLong()), sealed.size.toLong())
            assertArrayEquals(data, BackupStreams.open(sealed.inputStream(), keys, BackupKind.SYNC_FILE).plaintext.readBytes())
        }
    }

    @Test
    fun `the encrypting stream reads in small pieces too`() {
        val keys = keys()
        val data = randomBytes(10_000, 3)
        val stream = SyncEncryption(keys, segmentSize = 2048).wrap(data.inputStream())
        val out = java.io.ByteArrayOutputStream()
        val tiny = ByteArray(7)
        while (true) {
            val n = stream.read(tiny)
            if (n < 0) break
            out.write(tiny, 0, n)
        }
        assertArrayEquals(data, BackupStreams.open(out.toByteArray().inputStream(), keys, BackupKind.SYNC_FILE).plaintext.readBytes())
    }

    @Test
    fun `no secret is anywhere in the manifest`() {
        val provider = SafFolderProvider(fs())
        library.add(1, "a.jpg", randomBytes(100))
        run(engine(provider, encryption = SyncEncryption(keys())))
        assertFalse(store.text.contains("correct horse"))
        assertTrue(store.text.lines().all { it.isEmpty() || it.split('\t').first() in setOf("fotoz-sync-manifest", "B", "D") })
    }
}
