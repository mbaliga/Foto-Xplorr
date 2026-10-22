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
import org.junit.Before
import org.junit.Test

/**
 * "Check a backup" must read the newest data and must not say "everything is intact" about a
 * folder it only partly read. The scenario is the one the audit named: a second run finished its
 * packs and died while writing its manifest, so the newest snapshot is cut short and the newest
 * photos are in packs only their indexes list.
 */
class BackupCheckCoverageTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination

    @Before
    fun twoRuns() {
        dir = Files.createTempDirectory("fotoz-coverage-test").toFile()
        destination = DirectoryDestination(dir)
        backUp(6)
        backUp(10)
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun backUp(count: Int) {
        val (candidates, source) = JobFixtures.library(count)
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, source, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
    }

    /** The second run's manifest cut short, as when the phone died while writing it. */
    private fun cutNewestManifest() {
        val newest = File(dir, BackupLayout.manifestName(2))
        newest.writeBytes(newest.readBytes().copyOf(BackupFormat.HEADER_SIZE + 40))
    }

    private fun verify(): VerifyReport = runBlocking { BackupVerifier.verify(destination, newKeys(), io = Dispatchers.Unconfined) }

    private fun secondRunPacks(): List<String> {
        val first = runBlocking { BackupSetLoader.load(destination, newKeys()) }.manifest!!.packs.map { it.name }
        return destination.names().filter { BackupLayout.isPackName(it) && it !in first }.sorted()
    }

    @Test
    fun `a complete check says everything is intact`() {
        val report = verify()
        assertTrue(report.isClean)
        assertFalse(report.hasCaveats)
        assertEquals(10, report.filesVerified)
        assertTrue(BackupCopy.verifyClean(report), BackupCopy.verifyClean(report).endsWith("everything is intact."))
    }

    @Test
    fun `when the newest snapshot is cut short the finished packs of that run are read too`() {
        cutNewestManifest()
        val report = verify()

        assertTrue(report.isClean)
        assertEquals(1, report.skippedManifests)
        assertEquals(1, report.generation)
        assertTrue(report.recoveredPacks > 0)
        // Every photo is accounted for: the first run's through the older snapshot, the second run's through its indexes.
        assertEquals(10, report.filesVerified)
        assertEquals(destination.names().count { BackupLayout.isPackName(it) }, report.packsChecked)
    }

    @Test
    fun `a damaged pack that only an index lists is found, which the old check never opened`() {
        cutNewestManifest()
        val pack = secondRunPacks().last()
        val bytes = destination.bytes(pack)
        File(dir, pack).writeBytes(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 1).toByte() })

        val report = verify()
        assertFalse(report.isClean)
        assertTrue(report.problems.any { it.packName == pack && it.kind == ProblemKind.PACK_DAMAGED })
    }

    @Test
    fun `a clean check that stood on an older snapshot says so and never says everything is intact`() {
        cutNewestManifest()
        val report = verify()
        val text = BackupCopy.verifyClean(report)
        assertFalse(text, text.contains("everything is intact"))
        assertTrue(text, text.contains("The newest snapshot could not be read; checked the one before it plus"))
        assertTrue(text, text.contains("finished pack"))
        assertTrue(text, text.startsWith("Checked 10 files"))
    }

    @Test
    fun `finished packs no snapshot lists yet are said to be checked too`() {
        // The manifest of the second run is simply missing: nothing was skipped, but its packs are not in a snapshot.
        File(dir, BackupLayout.manifestName(2)).delete()
        val report = verify()
        assertEquals(0, report.skippedManifests)
        assertTrue(report.recoveredPacks > 0)
        val text = BackupCopy.verifyClean(report)
        assertFalse(text, text.contains("everything is intact"))
        assertTrue(text, text.contains("not in a snapshot yet"))
    }

    @Test
    fun `files the check could not use are counted and named as not checked`() {
        val stray = BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK)
        File(dir, BackupLayout.packName(77)).writeBytes(stray)
        val report = verify()
        assertEquals(1, report.leftovers)
        val text = BackupCopy.verifyClean(report)
        assertFalse(text, text.contains("everything is intact"))
        assertTrue(text, text.contains("1 file in the folder cannot be used and was not checked."))
    }

    @Test
    fun `the notes also reach a report that found problems`() {
        cutNewestManifest()
        val pack = secondRunPacks().first()
        File(dir, pack).delete()
        val report = verify()
        val text = BackupCopy.verifyProblems(report)
        assertTrue(text, text.startsWith("Checked the backup and found"))
        assertTrue(text, text.contains("The newest snapshot could not be read"))
    }

    @Test
    fun `a folder with finished packs but no manifest at all can still be checked`() {
        File(dir, BackupLayout.manifestName(1)).delete()
        File(dir, BackupLayout.manifestName(2)).delete()
        val report = verify()
        assertTrue(report.isClean)
        assertEquals(0, report.generation)
        assertEquals(10, report.filesVerified)
    }
}
