package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The location re-copy end to end over several runs against a real folder. The old pack of a
 * superseded copy stays in the folder with its valid index, so every later run reads it back as a
 * recovered one; the run must still settle on the newer record and stop copying.
 */
class BackupLocationConvergenceTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-location-test").toFile()
        destination = DirectoryDestination(dir)
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun runOnce(candidates: List<BackupCandidate>, source: FakeSource, locationReadable: Boolean): Pair<BackupPlan, BackupOutcome> {
        val keys = newKeys()
        val prep = try {
            runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits, locationReadable) }
        } finally {
            keys.close()
        }
        val outcome = runBlocking { BackupJob(destination, source, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
        return prep.plan to outcome
    }

    private fun load(): LoadedSet {
        val keys = newKeys()
        return try {
            runBlocking { BackupSetLoader.load(destination, keys) }
        } finally {
            keys.close()
        }
    }

    @Test
    fun `a backup made without locations is copied again once, and then stays up to date`() {
        val (candidates, source) = JobFixtures.library(3)

        source.original = false
        runOnce(candidates, source, locationReadable = false)
        val firstPacks = destination.names().filter { BackupLayout.isPackName(it) }
        assertTrue(load().manifest!!.entries.none { EntryFlag.ORIGINAL_LOCATION in it.flags })

        // The permission is granted: one more copy of each file.
        source.original = true
        val (plan2, outcome2) = runOnce(candidates, source, locationReadable = true)
        assertEquals(3, plan2.locationRecopies)
        assertEquals(3, outcome2.filesCopied)
        val packsAfter2 = destination.names().filter { BackupLayout.isPackName(it) }
        assertTrue("the old packs stay in the folder", packsAfter2.containsAll(firstPacks))
        assertTrue(load().manifest!!.entries.all { EntryFlag.ORIGINAL_LOCATION in it.flags })

        // The old pack is no longer in the manifest but is read back from its index: it must change nothing.
        assertTrue("the old pack comes back as a recovered one", load().recovered.isNotEmpty())
        repeat(3) { n ->
            val (plan, outcome) = runOnce(candidates, source, locationReadable = true)
            assertTrue("run ${n + 3} plans no copy", plan.isUpToDate)
            assertEquals(0, plan.locationRecopies)
            assertEquals(0, outcome.filesCopied)
            assertEquals("no pack is added in run ${n + 3}", packsAfter2.toSet(), destination.names().filter { BackupLayout.isPackName(it) }.toSet())
            val entries = load().manifest!!.entries
            assertEquals(3, entries.size)
            assertTrue(entries.all { EntryFlag.ORIGINAL_LOCATION in it.flags })
        }
    }
}
