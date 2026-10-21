package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * "Check a backup" against folders that have been damaged in each way a real one is: a flipped
 * bit, a truncation, a deleted pack, a swapped pack, the wrong passphrase. Every byte is read
 * through the authenticating stream; nothing is written.
 */
class BackupVerifierTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination
    private lateinit var source: FakeSource
    private lateinit var candidates: List<BackupCandidate>

    @Before
    fun backedUpFolder() {
        dir = Files.createTempDirectory("fotoz-verify-test").toFile()
        destination = DirectoryDestination(dir)
        val (c, s) = JobFixtures.library(9)
        candidates = c
        source = s
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, source, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun verify(keys: BackupKeys = newKeys()): VerifyReport =
        runBlocking { BackupVerifier.verify(destination, keys, io = Dispatchers.Unconfined) }

    private fun packs() = destination.names().filter { BackupLayout.isPackName(it) }.sorted()

    @Test
    fun `an intact backup is clean and every file is counted`() {
        val report = verify()
        assertTrue(report.isClean)
        assertEquals(9, report.filesVerified)
        assertEquals(packs().size, report.packsChecked)
        assertEquals(1, report.generation)
        assertTrue(report.bytesChecked >= 9 * 300)
    }

    @Test
    fun `a flipped bit in a pack is found, in any segment`() {
        val pack = packs()[1]
        val original = destination.bytes(pack)
        for (position in listOf(BackupFormat.HEADER_SIZE + 60, original.size / 2, original.size - 3)) {
            val damaged = original.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            File(dir, pack).writeBytes(damaged)
            val report = verify()
            assertFalse("flip at $position went unnoticed", report.isClean)
            assertTrue(report.problems.any { it.packName == pack && it.kind == ProblemKind.PACK_DAMAGED })
        }
        File(dir, pack).writeBytes(original)
        assertTrue(verify().isClean)
    }

    @Test
    fun `a truncated pack is found and its files are named`() {
        val pack = packs().first()
        val original = destination.bytes(pack)
        File(dir, pack).writeBytes(original.copyOf(original.size - 200))
        val report = verify()
        assertTrue(report.problems.any { it.kind == ProblemKind.PACK_SIZE })
        assertTrue(report.problems.any { it.kind == ProblemKind.PACK_DAMAGED })
        assertTrue(report.problems.any { it.kind == ProblemKind.FILE_NOT_FOUND && it.fileName != null })
        // The other packs are still reported as good.
        assertTrue(report.filesVerified in 1 until 9)
    }

    @Test
    fun `a deleted pack is reported with its files`() {
        val pack = packs().last()
        File(dir, pack).delete()
        val report = verify()
        assertTrue(report.problems.any { it.kind == ProblemKind.PACK_MISSING && it.packName == pack })
        assertTrue(report.problems.any { it.kind == ProblemKind.FILE_NOT_FOUND && it.packName == pack })
    }

    @Test
    fun `one pack swapped for another is found`() {
        val packs = packs()
        val a = destination.bytes(packs[0])
        val b = destination.bytes(packs[1])
        File(dir, packs[0]).writeBytes(b)
        File(dir, packs[1]).writeBytes(a)
        val report = verify()
        assertFalse(report.isClean)
    }

    @Test
    fun `a pack made by another set cannot stand in for one of this set`() {
        val pack = packs().first()
        val foreign = java.io.ByteArrayOutputStream()
        val otherKeys = BackupTestSupport.newKeys(BackupTestSupport.otherSalt, "another passphrase entirely")
        BackupStreams.encrypt(foreign, BackupKind.PACK, otherKeys, BackupTestSupport.SEGMENT).use { it.write(ByteArray(400)) }
        File(dir, pack).writeBytes(foreign.toByteArray())
        val report = verify()
        assertTrue(report.problems.any { it.packName == pack })
    }

    @Test
    fun `a wrong passphrase is refused before anything is checked`() {
        val wrong = BackupTestSupport.newKeys(passphrase = "definitely not the passphrase")
        try {
            runBlocking { BackupVerifier.verify(destination, wrong, io = Dispatchers.Unconfined) }
            fail("a wrong passphrase must not look like a clean check")
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            // expected
        }
        assertTrue(wrong.isWiped)
    }

    @Test
    fun `a folder with no manifest has nothing to check`() {
        destination.names().filter { BackupLayout.manifestGeneration(it) != null }.forEach { File(dir, it).delete() }
        try {
            verify()
            fail("there is no finished backup")
        } catch (e: BackupRefused) {
            assertTrue(e.message!!.contains("nothing to check"))
        }
    }

    @Test
    fun `the check writes nothing and deletes nothing`() {
        val before = destination.snapshot()
        verify()
        assertEquals(before, destination.snapshot())
        assertTrue(destination.deleted.isEmpty())
        assertEquals(before.size, destination.created.size)
    }

    @Test
    fun `a file whose bytes differ from the manifest is a mismatch even if the pack authenticates`() {
        // Rewrite one pack with a fresh, valid stream whose first record has other bytes.
        val pack = packs().first()
        val loaded = runBlocking { BackupSetLoader.load(destination, newKeys()) }
        val entry = loaded.manifest!!.entries.first { it.packName == pack }
        val forged = java.io.ByteArrayOutputStream()
        val keys = newKeys()
        BackupStreams.encrypt(forged, BackupKind.PACK, keys, BackupTestSupport.SEGMENT).use { out ->
            val other = ByteArray(entry.sizeBytes.toInt()) { 9 }
            out.write(PackRecord.encodeHeader(entry.entryId, other.size.toLong()))
            out.write(other)
            out.write(java.security.MessageDigest.getInstance("SHA-256").digest(other))
        }
        File(dir, pack).writeBytes(forged.toByteArray())
        val report = verify()
        assertTrue(report.problems.any { it.kind == ProblemKind.FILE_MISMATCH && it.fileName == entry.displayName })
    }

    @Test
    fun `progress ends at one`() {
        val seen = ArrayList<Float>()
        runBlocking { BackupVerifier.verify(destination, newKeys(), onProgress = { seen += it }, io = Dispatchers.Unconfined) }
        assertEquals(1f, seen.last(), 0.0001f)
    }
}
