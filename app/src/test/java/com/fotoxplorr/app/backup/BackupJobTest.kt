package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The writer end to end on the JVM: planner, packs, indexes, manifest, resume, cancel, a full
 * disk, a file that changes underfoot, and what the verifier says afterwards. The folder is a plain
 * directory ([DirectoryDestination]); segments are 128 bytes and packs hold a few files, so the
 * interesting boundaries are crossed with a few hundred bytes of test data.
 */
class BackupJobTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-backup-test").toFile()
        destination = DirectoryDestination(dir)
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun prepare(candidates: List<BackupCandidate>, source: FakeSource, options: BackupOptions = BackupOptions()): BackupPreparation {
        val keys = newKeys()
        return try {
            runBlocking { BackupPreparer.prepare(destination, keys, candidates, options, source, JobFixtures.tinyLimits) }
        } finally {
            keys.close()
        }
    }

    private fun job(source: FakeSource, sections: List<ManifestSection> = emptyList(), keys: BackupKeys = newKeys()) =
        BackupJob(destination, source, keys, appVersion = "test", sections = { sections }, io = Dispatchers.Unconfined)

    private fun run(candidates: List<BackupCandidate>, source: FakeSource, sections: List<ManifestSection> = emptyList()): BackupOutcome {
        val prep = prepare(candidates, source)
        return runBlocking { job(source, sections).run(prep) }
    }

    private fun load(): LoadedSet {
        val keys = newKeys()
        return try {
            runBlocking { BackupSetLoader.load(destination, keys) }
        } finally {
            keys.close()
        }
    }

    private fun verify(): VerifyReport = runBlocking { BackupVerifier.verify(destination, newKeys(), io = Dispatchers.Unconfined) }

    @Test
    fun `a first run writes packs, an index beside each and a manifest that lists every file`() {
        val (candidates, source) = JobFixtures.library(7)
        val outcome = run(candidates, source)

        assertEquals(7, outcome.filesCopied)
        assertTrue("several packs", outcome.packsWritten > 1)
        assertEquals(1, outcome.generation)
        val names = destination.names()
        val packs = names.filter { BackupLayout.isPackName(it) }
        assertEquals(outcome.packsWritten, packs.size)
        packs.forEach { pack ->
            val number = BackupLayout.packNumber(pack)!!
            assertTrue("$pack has its index", BackupLayout.indexName(number) in names)
        }
        assertTrue(BackupLayout.manifestName(1) in names)

        val loaded = load()
        assertNotNull(loaded.manifest)
        assertEquals(7, loaded.manifest!!.entries.size)
        assertEquals(candidates.map { it.displayName }.toSet(), loaded.manifest!!.entries.map { it.displayName }.toSet())
        assertTrue(loaded.leftovers.isEmpty())
        assertTrue(loaded.recovered.isEmpty())
        val report = verify()
        assertTrue(report.problems.joinToString { it.detail }, report.isClean)
        assertEquals(7, report.filesVerified)
    }

    @Test
    fun `nothing in the folder is plaintext`() {
        val (candidates, source) = JobFixtures.library(4)
        val marker = "SECRET-NAME-OF-A-PHOTO".toByteArray()
        source.put(1, marker + ByteArray(200))
        run(candidates.map { if (it.mediaId == 1L) it.copy(displayName = "SECRET-NAME-OF-A-PHOTO.jpg") else it }, source)
        destination.names().forEach { name ->
            val bytes = destination.bytes(name)
            assertFalse("$name holds a photo's bytes", contains(bytes, marker))
            assertFalse("$name holds a photo's name", contains(bytes, "SECRET-NAME".toByteArray()))
            assertFalse("$name holds a folder name", contains(bytes, "DCIM".toByteArray()))
        }
    }

    @Test
    fun `a second run with nothing new writes no pack and a newer manifest, and changes no existing file`() {
        val (candidates, source) = JobFixtures.library(5)
        run(candidates, source)
        val before = destination.snapshot()

        val outcome = run(candidates, source)

        assertEquals(0, outcome.filesCopied)
        assertEquals(0, outcome.packsWritten)
        assertEquals(5, outcome.alreadyBackedUp)
        assertEquals(2, outcome.generation)
        val after = destination.snapshot()
        before.forEach { (name, sha) -> assertEquals("$name was changed", sha, after[name]) }
        assertEquals(before.keys + BackupLayout.manifestName(2), after.keys)
        assertTrue(destination.deleted.isEmpty())
        assertTrue(verify().isClean)
    }

    @Test
    fun `only new and changed files are copied and a changed file keeps its old copy`() {
        val (candidates, source) = JobFixtures.library(4)
        run(candidates, source)
        val firstPacks = destination.names().filter { BackupLayout.isPackName(it) }.toSet()

        source.put(5, BackupTestSupport.plaintext(300, seed = 55))
        source.put(2, BackupTestSupport.plaintext(310, seed = 22))
        val next = candidates.map { if (it.mediaId == 2L) it.copy(sizeBytes = 310, dateModifiedSeconds = 9_999) else it } +
            JobFixtures.candidate(5, size = 300)
        val outcome = run(next, source)

        assertEquals(2, outcome.filesCopied)
        val packs = destination.names().filter { BackupLayout.isPackName(it) }.toSet()
        assertTrue("the first run's packs stay", packs.containsAll(firstPacks))
        assertTrue("and new ones are added", packs.size > firstPacks.size)
        val manifest = load().manifest!!
        assertEquals(5, manifest.entries.size)
        assertEquals(310, manifest.entries.first { it.mediaId == 2L }.sizeBytes)
        assertTrue(verify().isClean)
    }

    @Test
    fun `a file deleted from the phone stays in the backup`() {
        val (candidates, source) = JobFixtures.library(4)
        run(candidates, source)
        val outcome = run(candidates.filter { it.mediaId != 3L }, source)
        assertEquals(0, outcome.filesCopied)
        val manifest = load().manifest!!
        assertTrue(manifest.entries.any { it.mediaId == 3L })
    }

    @Test
    fun `each file's sha256 and quick hash are those of its bytes`() {
        val (candidates, source) = JobFixtures.library(3, size = 1_000)
        run(candidates, source)
        val manifest = load().manifest!!
        for (entry in manifest.entries) {
            val bytes = source.bytes(entry.mediaId)
            assertEquals(sha(bytes), entry.sha256)
            val head = bytes.copyOfRange(0, QuickHash.headLength(bytes.size.toLong()))
            val tail = bytes.copyOfRange(QuickHash.tailOffset(bytes.size.toLong()).toInt(), bytes.size)
            assertEquals(QuickHash.of(bytes.size.toLong(), head, tail), entry.quickHash)
            assertTrue(EntryFlag.ORIGINAL_LOCATION in entry.flags)
        }
    }

    @Test
    fun `a photo read without its location is recorded as having none`() {
        val (candidates, source) = JobFixtures.library(2)
        source.original = false
        run(candidates, source)
        assertTrue(load().manifest!!.entries.none { EntryFlag.ORIGINAL_LOCATION in it.flags })
    }

    @Test
    fun `protected, sensitive and archived photos go in with their flags and the trashed one does not`() {
        val (plain, source) = JobFixtures.library(4)
        val marked = plain.mapIndexed { i, c ->
            when (i) {
                0 -> c.copy(isProtected = true)
                1 -> c.copy(isSensitive = true)
                2 -> c.copy(isArchived = true)
                else -> c.copy(isTrashed = true)
            }
        }
        run(marked, source)
        val entries = load().manifest!!.entries.associateBy { it.mediaId }
        assertEquals(setOf(1L, 2L, 3L), entries.keys)
        assertTrue(EntryFlag.WAS_PROTECTED in entries.getValue(1).flags)
        assertTrue(EntryFlag.WAS_SENSITIVE in entries.getValue(2).flags)
        assertTrue(EntryFlag.WAS_ARCHIVED in entries.getValue(3).flags)
    }

    @Test
    fun `the manifest carries the sections it was given`() {
        val (candidates, source) = JobFixtures.library(2)
        val sections = BackupSections.build(
            metadata = com.fotoxplorr.app.editor.parseJson("""{"kind":"x"}""")!!,
            locks = listOf(BackupSections.Lock("path:dcim/private", "c2FsdA==", "aGFzaA==")),
            pins = listOf(BackupSections.Pin(1, 51.5, -0.12)),
            recipes = emptyList(),
        )
        run(candidates, source, sections)
        val manifest = load().manifest!!
        assertEquals(listOf("path:dcim/private"), BackupSections.locks(manifest.section(BackupSections.LOCKS)).map { it.folderKey })
        assertEquals(1L, BackupSections.pins(manifest.section(BackupSections.PINS)).single().mediaId)
    }

    @Test
    fun `a file that cannot be opened is reported and the rest are saved`() {
        val (candidates, source) = JobFixtures.library(4)
        source.missing += 2L
        val outcome = run(candidates, source)
        assertEquals(3, outcome.filesCopied)
        assertEquals(listOf(2L), outcome.failed.map { it.candidate.mediaId })
        assertEquals(CopyFailure.UNREADABLE, outcome.failed.single().reason)
        assertTrue(load().manifest!!.entries.none { it.mediaId == 2L })
        assertTrue(verify().isClean)
    }

    @Test
    fun `a file that is shorter than it said is reported, not saved wrong, and its pack still verifies`() {
        val (candidates, source) = JobFixtures.library(4)
        // The provider claims 300 bytes; the stream delivers 120 of them.
        source.wrap = { id, stream -> if (id == 2L) ByteArrayInputStream(stream.readBytes().copyOf(120)) else stream }
        val outcome = run(candidates, source)
        assertEquals(CopyFailure.CHANGED_WHILE_BACKING_UP, outcome.failed.single().reason)
        assertEquals(3, outcome.filesCopied)
        assertTrue(load().manifest!!.entries.none { it.mediaId == 2L })
        val report = verify()
        assertTrue(report.problems.joinToString { it.detail }, report.isClean)
    }

    @Test
    fun `a file that grew while it was read is reported and not saved cut off`() {
        val (candidates, source) = JobFixtures.library(3)
        source.reportedLength = { id, bytes -> if (id == 2L) bytes.size - 50L else bytes.size.toLong() }
        val outcome = run(candidates, source)
        assertEquals(CopyFailure.CHANGED_WHILE_BACKING_UP, outcome.failed.single().reason)
        assertTrue(load().manifest!!.entries.none { it.mediaId == 2L })
        assertTrue(verify().isClean)
    }

    @Test
    fun `a read error half way through a file is reported and the pack stays well formed`() {
        val (candidates, source) = JobFixtures.library(3)
        source.wrap = { id, stream ->
            if (id != 2L) {
                stream
            } else {
                object : InputStream() {
                    var served = 0
                    override fun read(): Int = throw IOException("bad sector")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served >= 100) throw IOException("bad sector")
                        val n = stream.read(b, off, minOf(len, 100 - served))
                        if (n > 0) served += n
                        return n
                    }
                }
            }
        }
        val outcome = run(candidates, source)
        assertEquals(CopyFailure.UNREADABLE, outcome.failed.single().reason)
        assertTrue(verify().isClean)
    }

    @Test
    fun `a file whose real length differs from the scan is saved as it is now`() {
        val (candidates, source) = JobFixtures.library(2)
        val bigger = BackupTestSupport.plaintext(420, seed = 99)
        source.put(2, bigger)
        run(candidates, source)
        val entry = load().manifest!!.entries.first { it.mediaId == 2L }
        assertEquals(420, entry.sizeBytes)
        assertEquals(sha(bigger), entry.sha256)
    }

    @Test
    fun `a file of unknown length is copied by its scanned size`() {
        val (candidates, source) = JobFixtures.library(2)
        source.reportedLength = { _, _ -> -1L }
        val outcome = run(candidates, source)
        assertEquals(2, outcome.filesCopied)
        assertTrue(verify().isClean)
    }

    @Test
    fun `cancelling mid-run leaves finished packs usable, deletes the half-written one and rethrows the cancellation`() {
        val (candidates, source) = JobFixtures.library(9)
        val prep = prepare(candidates, source)
        assertTrue(prep.plan.packs.size >= 3)
        val firstPackFiles = prep.plan.packs.first().copies.size
        var opens = 0
        lateinit var deferred: kotlinx.coroutines.Deferred<BackupOutcome>
        source.onOpen = {
            opens++
            // Cancel while the second pack's first file is being opened.
            if (opens == firstPackFiles + 1) deferred.cancel()
        }
        var thrown: Throwable? = null
        runBlocking {
            deferred = async(start = CoroutineStart.LAZY) { job(source).run(prep) }
            try {
                deferred.await()
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("cancellation, not a failure message: $thrown", thrown is CancellationException)

        val names = destination.names()
        val firstPack = prep.plan.packs.first().name
        assertTrue(firstPack in names)
        assertTrue(BackupLayout.indexName(BackupLayout.packNumber(firstPack)!!) in names)
        val second = prep.plan.packs[1].name
        assertFalse("the half-written pack was removed", second in names)
        // A manifest was written for what was finished, so the folder is a set as it stands.
        val loaded = load()
        assertEquals(firstPackFiles, loaded.manifest!!.entries.size)
        assertTrue(loaded.leftovers.isEmpty())
        assertTrue(verify().isClean)
    }

    @Test
    fun `a run that carries on after a cancel copies only what is missing and never repeats a finished pack`() {
        val (candidates, source) = JobFixtures.library(9)
        val prep = prepare(candidates, source)
        val firstPackFiles = prep.plan.packs.first().copies.size
        var opens = 0
        lateinit var deferred: kotlinx.coroutines.Deferred<BackupOutcome>
        source.onOpen = {
            opens++
            if (opens == firstPackFiles + 1) deferred.cancel()
        }
        runBlocking {
            deferred = async(start = CoroutineStart.LAZY) { job(source).run(prep) }
            runCatching { deferred.await() }
        }
        val packsAfterCancel = destination.names().filter { BackupLayout.isPackName(it) }.toSet()
        source.onOpen = {}
        source.opened.clear()

        val outcome = run(candidates, source)

        assertEquals(9 - firstPackFiles, outcome.filesCopied)
        assertTrue("the finished pack was not read again", source.opened.none { it <= firstPackFiles })
        assertTrue(destination.names().containsAll(packsAfterCancel))
        assertEquals(9, load().manifest!!.entries.size)
        assertTrue(verify().isClean)
    }

    @Test
    fun `when the manifest was never written the indexes alone resume the run (process death between packs)`() {
        val (candidates, source) = JobFixtures.library(9)
        val prep = prepare(candidates, source)
        val firstPackFiles = prep.plan.packs.first().copies.size
        var opens = 0
        lateinit var deferred: kotlinx.coroutines.Deferred<BackupOutcome>
        source.onOpen = {
            opens++
            if (opens == firstPackFiles + 1) deferred.cancel()
        }
        runBlocking {
            deferred = async(start = CoroutineStart.LAZY) { job(source).run(prep) }
            runCatching { deferred.await() }
        }
        // The process died before it could write the manifest: take that file away.
        destination.names().filter { BackupLayout.manifestGeneration(it) != null }.forEach { File(dir, it).delete() }
        source.onOpen = {}
        source.opened.clear()

        val loaded = load()
        assertNull(loaded.manifest)
        assertEquals(1, loaded.recovered.size)
        assertEquals(firstPackFiles, loaded.known.size)

        val outcome = run(candidates, source)
        assertEquals(9 - firstPackFiles, outcome.filesCopied)
        assertTrue(source.opened.none { it <= firstPackFiles })
        assertEquals(9, load().manifest!!.entries.size)
        assertTrue(verify().isClean)
    }

    @Test
    fun `a pack with no index is a leftover that is never deleted and whose number is not reused`() {
        val (candidates, source) = JobFixtures.library(5)
        run(candidates.take(3), source)
        val packs = destination.names().filter { BackupLayout.isPackName(it) }.sorted()
        val last = packs.last()
        // The run died inside the next pack: a pack file exists, with no index.
        val orphan = BackupLayout.packName(BackupLayout.packNumber(last)!! + 1)
        File(dir, orphan).writeBytes(ByteArray(500) { 7 })

        val loaded = load()
        assertEquals(listOf(orphan), loaded.leftovers.map { it.name })

        run(candidates, source)
        assertTrue("the orphan is still there", File(dir, orphan).isFile)
        assertEquals(500, File(dir, orphan).length())
        val newest = destination.names().filter { BackupLayout.isPackName(it) }.maxOf { BackupLayout.packNumber(it)!! }
        assertTrue(newest > BackupLayout.packNumber(orphan)!!)
        assertTrue(destination.deleted.none { it == orphan })
    }

    @Test
    fun `an index whose pack is gone reserves its number`() {
        val (candidates, source) = JobFixtures.library(3)
        run(candidates, source)
        val pack = destination.names().first { BackupLayout.isPackName(it) }
        File(dir, pack).delete()
        File(dir, BackupLayout.manifestName(1)).delete()
        val loaded = load()
        assertTrue(BackupLayout.packNumber(pack)!! in loaded.reservedPackNumbers)
        assertTrue(loaded.leftovers.any { BackupLayout.isIndexName(it.name) })

        val prep = prepare(candidates, source)
        val newNumbers = prep.plan.packs.map { BackupLayout.packNumber(it.name)!! }
        assertTrue(newNumbers.none { it in loaded.reservedPackNumbers })
        val outcome = runBlocking { job(source).run(prep) }
        assertEquals(3, outcome.filesCopied)
    }

    @Test
    fun `a full disk stops the run with a plain sentence, keeps finished packs and removes the half-written one`() {
        val (candidates, source) = JobFixtures.library(9)
        val prep = prepare(candidates, source)
        val firstPack = prep.plan.packs.first()
        // The second pack does not fit: its writes fail once it has been started.
        destination.failWritesTo = setOf(prep.plan.packs[1].name)
        var failure: Throwable? = null
        try {
            runBlocking { job(source).run(prep) }
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue("$failure", failure is BackupRefused)
        assertTrue(failure!!.message!!.contains("full"))
        assertTrue(failure.message!!.contains("carries on"))

        val names = destination.names()
        assertTrue(firstPack.name in names)
        assertFalse(prep.plan.packs[1].name in names)
        destination.failWritesTo = emptySet()
        val loaded = load()
        assertEquals(firstPack.copies.size, loaded.known.size)
        assertTrue(loaded.leftovers.isEmpty())

        val outcome = run(candidates, source)
        assertEquals(9 - firstPack.copies.size, outcome.filesCopied)
        assertTrue(verify().isClean)
    }

    @Test
    fun `an unwritable folder is a sentence about the folder, not a stack trace`() {
        val (candidates, source) = JobFixtures.library(2)
        val prep = prepare(candidates, source)
        dir.deleteRecursively()
        var failure: Throwable? = null
        try {
            runBlocking { job(source).run(prep) }
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue("$failure", failure is BackupRefused)
        assertTrue(failure!!.message!!.contains("could not be written"))
    }

    @Test
    fun `the run closes the keys it was given, however it ends`() {
        val (candidates, source) = JobFixtures.library(2)
        val prep = prepare(candidates, source)
        val keys = newKeys()
        runBlocking { job(source, keys = keys).run(prep) }
        assertTrue(keys.isWiped)

        val failing = newKeys()
        dir.deleteRecursively()
        runCatching { runBlocking { job(source, keys = failing).run(prep) } }
        assertTrue(failing.isWiped)
    }

    @Test
    fun `progress reaches one and never goes backwards`() {
        val (candidates, source) = JobFixtures.library(6)
        val prep = prepare(candidates, source)
        val seen = ArrayList<BackupProgress>()
        runBlocking { job(source).run(prep) { seen += it } }
        assertTrue(seen.isNotEmpty())
        assertEquals(1f, seen.last().fraction, 0.0001f)
        seen.zipWithNext().forEach { (a, b) -> assertTrue(b.fraction >= a.fraction) }
        assertEquals(prep.plan.bytesToCopy, seen.last().bytesTotal)
    }

    @Test
    fun `a wrong passphrase writes nothing`() {
        val (candidates, source) = JobFixtures.library(3)
        run(candidates, source)
        val before = destination.snapshot()
        val wrong = BackupTestSupport.newKeys(passphrase = "not the right passphrase at all")
        try {
            runBlocking { BackupPreparer.prepare(destination, wrong, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
            fail("a wrong passphrase must be refused")
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            // expected
        } finally {
            wrong.close()
        }
        assertEquals(before, destination.snapshot())
        assertTrue(destination.created.size == before.size)
    }

    @Test
    fun `a half-written newest manifest is ignored and the generation before it wins`() {
        val (candidates, source) = JobFixtures.library(3)
        run(candidates, source)
        // Generation 2 died while being written: a cut copy of generation 1.
        val first = destination.bytes(BackupLayout.manifestName(1))
        File(dir, BackupLayout.manifestName(2)).writeBytes(first.copyOf(first.size - 60))

        val loaded = load()
        assertEquals(1, loaded.manifest!!.header.generation)
        assertEquals(1, loaded.skippedManifests)
        assertEquals(2, loaded.highestGeneration)

        val outcome = run(candidates, source)
        assertEquals(3, outcome.generation)
        assertEquals(0, outcome.filesCopied)
    }

    @Test
    fun `a listing that fails stops the run instead of looking like an empty folder`() {
        val (candidates, source) = JobFixtures.library(3)
        run(candidates, source)
        destination.listFailure = IOException("The folder could not be read")
        val keys = newKeys()
        try {
            runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
            fail("a failed listing must stop the run")
        } catch (e: IOException) {
            assertEquals("The folder could not be read", e.message)
        } finally {
            keys.close()
        }
    }

    @Test
    fun `a folder that fails to read a manifest is not mistaken for a damaged one`() {
        val (candidates, source) = JobFixtures.library(3)
        run(candidates, source)
        run(candidates, source)
        destination.openFailure = { name -> if (BackupLayout.manifestGeneration(name) == 2) IOException("card removed") else null }
        val keys = newKeys()
        try {
            runBlocking { BackupSetLoader.load(destination, keys) }
            fail("falling back to generation 1 would write a snapshot that forgets files")
        } catch (e: IOException) {
            assertEquals("card removed", e.message)
        } finally {
            keys.close()
        }
    }

    @Test
    fun `a listing that quietly comes back empty cannot overwrite or remove anything`() {
        val (candidates, source) = JobFixtures.library(6)
        run(candidates, source)
        val before = destination.snapshot()
        // The card hiccups and lists nothing: the run believes the folder is new and tries to
        // write packs and a manifest under names that exist. A destination never overwrites.
        destination.listingOverride = emptyList()
        var failure: Throwable? = null
        try {
            run(candidates.filter { it.mediaId > 3 }, source)
        } catch (e: Throwable) {
            failure = e
        }
        destination.listingOverride = null
        assertTrue("$failure", failure is BackupRefused)
        assertEquals(before, destination.snapshot())
        assertTrue(destination.deleted.none { it in before.keys })
    }

    @Test
    fun `the oversize file is reported, skipped and does not stop the others`() {
        val (candidates, source) = JobFixtures.library(3)
        val huge = JobFixtures.candidate(10, name = "HUGE.mov", size = 5_000_000)
        val limits = PackLimits(targetBytes = 1_400, hardCapBytes = 200_000, segmentSize = BackupTestSupport.SEGMENT)
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates + huge, BackupOptions(), source, limits) }
        keys.close()
        val outcome = runBlocking { job(source).run(prep) }
        assertEquals(3, outcome.filesCopied)
        assertEquals(listOf("HUGE.mov"), outcome.tooLarge.map { it.candidate.displayName })
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
