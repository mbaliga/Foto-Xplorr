package com.fotoxplorr.app.sources.runtime

import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.sources.saf.EnrichmentOutcome
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.db.sources.IndexRunRecord
import com.fotoxplorr.core.db.sources.SourceRecord
import com.fotoxplorr.core.index.walker.TreeFailure
import com.fotoxplorr.core.index.walker.WalkBudget
import com.fotoxplorr.core.index.walker.WalkConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stands in for the process dying between two slices. An Error, so nothing in the indexer can swallow it. */
private class ProcessDeath : Error()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceIndexerTest {
    private val tree = "content://auth/tree/primary%3ADCIM"
    private lateinit var db: FotozSourcesDatabase
    private lateinit var grants: FakeGrants
    private lateinit var meta: InMemorySourceMetaStore
    private var now = 1_000_000L

    @Before fun setUp() {
        db = inMemorySourcesDb(ApplicationProvider.getApplicationContext())
        grants = FakeGrants(tree)
        meta = InMemorySourceMetaStore()
    }

    @After fun tearDown() = db.close()

    private fun addSource(state: String = "ONLINE", locator: String = tree): Long = runBlocking {
        db.sourcesDao().insert(SourceRecord(kind = "SAF_TREE", rootLocator = locator, volumeUuid = "primary", displayName = "DCIM", state = state, createdMs = 1))
    }

    private fun indexer(
        t: MemoryTree,
        facts: FakeFacts = FakeFacts(),
        config: WalkConfig = WalkConfig(),
        enrich: EnrichConfig = EnrichConfig(localAuthorities = setOf("auth")),
        hook: (Int) -> Unit = {},
        g: SourceGrants = grants,
    ) = SourceIndexer(
        db = db, grants = g, treeFor = { t }, documentUri = { _, id -> "content://auth/document/$id" }, facts = facts.reader, meta = meta,
        clock = { now }, walkConfig = config, sliceBudget = WalkBudget(maxDirectories = 8), enrichConfig = enrich, sliceHook = hook,
    )

    private suspend fun presentIds(row: Long) = db.sourceAssetsDao().forSource(row).filter { it.present }.map { it.documentId }.toSet()

    @Test
    fun `five thousand fake files are indexed in one listing per directory and sized`() = runBlocking {
        val t = MemoryTree.generate(5_000)
        val row = addSource()
        val facts = FakeFacts()
        val outcome = indexer(t, facts).index(row) as IndexOutcome.Completed
        assertEquals(5_000L, outcome.files)
        assertEquals(5_000, db.sourceAssetsDao().count(row))
        assertEquals(t.dirs.size, t.listings)
        assertEquals(t.dirs.size, t.listed.toSet().size)
        assertEquals(5_000, outcome.sized)
        assertEquals(0, outcome.unreadable)
        val all = db.sourceAssetsDao().forSource(row)
        assertTrue(all.all { it.width == 400 && it.height == 300 && it.enrichState == 1 && it.dateTakenMs == 1_650_000_000_000L })
        assertEquals("ONLINE", db.sourcesDao().get(row)!!.state)
        assertEquals(now, meta.get(row).lastIndexedMs)
        val run = db.indexRunsDao().get(1)!!
        assertEquals("COMPLETED", run.status)
        assertTrue(run.full)
        assertNull(db.indexRunsDao().latestRunning(row))
    }

    @Test
    fun `completing records the count and drops the cursor and the error`() = runBlocking {
        val t = MemoryTree.generate(120)
        val row = addSource()
        now = 5_000_000L
        val outcome = indexer(t).index(row) as IndexOutcome.Completed
        val run = db.indexRunsDao().get(1)!!
        assertEquals("COMPLETED", run.status)
        assertEquals(outcome.files, run.filesSeen)
        assertEquals(5_000_000L, run.finishedMs)
        assertNull(run.cursor)
        assertNull(run.error)
        assertTrue(run.full)
    }

    @Test
    fun `a run something else already ended is not reopened by the indexer`() = runBlocking {
        val t = MemoryTree.generate(2_000)
        val row = addSource()
        val outcome = indexer(t, hook = { if (it == 2) runBlocking { db.indexRunsDao().finish(1, "CANCELLED", 9, "stopped elsewhere") } }).index(row)
        assertTrue(outcome is IndexOutcome.Completed)
        val run = db.indexRunsDao().get(1)!!
        assertEquals("the guarded complete leaves a finished run alone", "CANCELLED", run.status)
        assertEquals("stopped elsewhere", run.error)
        // and so a run that is not COMPLETED+full can never sweep
        assertEquals(0, (outcome as IndexOutcome.Completed).swept)
    }

    @Test
    fun `a walk killed mid-way and resumed gives the same set as one that was not`() = runBlocking {
        val t = MemoryTree.generate(5_000)
        val row = addSource()
        try {
            indexer(t, hook = { if (it == 3) throw ProcessDeath() }).index(row)
            error("the walk should have been killed")
        } catch (e: ProcessDeath) {
            // the process died here
        }
        val running = db.indexRunsDao().latestRunning(row)
        assertNotNull(running)
        assertNotNull("a checkpoint was saved with the files", running!!.cursor)
        val partial = db.sourceAssetsDao().count(row)
        assertTrue("some files, not all: $partial", partial in 1 until 5_000)

        t.listings = 0
        t.listed.clear()
        val resumed = indexer(t).index(row) as IndexOutcome.Completed
        assertEquals(5_000L, resumed.files)
        assertTrue("the resumed walk did not list everything again", t.listings < t.dirs.size)

        val reference = addSource(locator = "content://auth/tree/primary%3AOTHER")
        grants.heldUris += "content://auth/tree/primary%3AOTHER"
        indexer(MemoryTree.generate(5_000)).index(reference)
        assertEquals(presentIds(reference), presentIds(row))
        // The resumed run is the one that was left running, not a second walk.
        assertEquals(running.runId, db.indexRunsDao().get(running.runId)!!.runId)
        assertEquals("COMPLETED", db.indexRunsDao().get(running.runId)!!.status)
    }

    @Test
    fun `a rescan keeps every row number so media ids never change`() = runBlocking {
        val t = MemoryTree.generate(300)
        val row = addSource()
        indexer(t).index(row)
        val before = db.sourceAssetsDao().forSource(row).associate { it.documentId to it.assetRow }
        indexer(t).index(row)
        val after = db.sourceAssetsDao().forSource(row).associate { it.documentId to it.assetRow }
        assertEquals(before, after)
        assertEquals(300, after.size)
    }

    @Test
    fun `a file that is gone is marked not present after a completed full walk and its row stays`() = runBlocking {
        val t = MemoryTree.generate(200)
        val row = addSource()
        indexer(t).index(row)
        val gone = t.dirs.getValue("root").filterNot { it.isDirectory }.take(3).map { it.documentId }.toSet()
        t.dirs["root"] = t.dirs.getValue("root").filterNot { it.documentId in gone }
        val outcome = indexer(t).index(row) as IndexOutcome.Completed
        assertEquals(gone.size, outcome.swept)
        assertEquals(200, db.sourceAssetsDao().count(row))
        assertEquals(200 - gone.size, presentIds(row).size)
        assertTrue(presentIds(row).intersect(gone).isEmpty())
    }

    @Test
    fun `a revoked grant mid-run keeps every row and sweeps nothing`() = runBlocking {
        val t = MemoryTree.generate(300)
        val row = addSource()
        indexer(t).index(row)
        val gone = t.dirs.getValue("root").filterNot { it.isDirectory }.take(3).map { it.documentId }.toSet()
        t.dirs["root"] = t.dirs.getValue("root").filterNot { it.documentId in gone }
        var listings = 0
        t.beforeListing = { if (++listings == 4) grants.heldUris -= tree }
        val outcome = indexer(t, g = grants).index(row)
        assertTrue(outcome is IndexOutcome.Failed)
        assertEquals(com.fotoxplorr.app.sources.saf.SafHealth.REVOKED, (outcome as IndexOutcome.Failed).health)
        assertEquals("REVOKED", db.sourcesDao().get(row)!!.state)
        assertEquals("no row was removed", 300, db.sourceAssetsDao().count(row))
        assertEquals("nothing was marked missing", 300, presentIds(row).size)
    }

    @Test
    fun `a grant lost after the last listing still does not sweep`() = runBlocking {
        val t = MemoryTree.generate(60)
        val row = addSource()
        indexer(t).index(row)
        val gone = t.dirs.getValue("root").filterNot { it.isDirectory }.take(2).map { it.documentId }.toSet()
        t.dirs["root"] = t.dirs.getValue("root").filterNot { it.documentId in gone }
        // Revoked right after the very last directory was listed.
        val last = t.dirs.keys.maxOrNull()!!
        var seen = 0
        t.beforeListing = { seen++; if (seen == t.dirs.size) grants.heldUris -= tree }
        val outcome = indexer(t).index(row)
        assertTrue("$outcome", outcome is IndexOutcome.Failed)
        assertEquals(60, presentIds(row).size)
        assertEquals("REVOKED", db.sourcesDao().get(row)!!.state)
        assertNotNull(last)
    }

    @Test
    fun `a failed walk never sweeps and the next one resumes where it stopped`() = runBlocking {
        val t = MemoryTree.generate(400)
        val row = addSource()
        indexer(t).index(row)
        val gone = t.dirs.getValue("root").filterNot { it.isDirectory }.take(4).map { it.documentId }.toSet()
        t.dirs["root"] = t.dirs.getValue("root").filterNot { it.documentId in gone }
        t.failAt = "root/d1"
        val failed = indexer(t).index(row) as IndexOutcome.Failed
        assertEquals(com.fotoxplorr.app.sources.saf.SafHealth.OFFLINE, failed.health)
        assertEquals("OFFLINE", db.sourcesDao().get(row)!!.state)
        assertEquals(400, presentIds(row).size)
        assertNotNull("the checkpoint is kept so the drive can be replugged", db.indexRunsDao().latestRunning(row))
        assertNotNull(meta.get(row).lastError)

        t.failAt = null
        val ok = indexer(t).index(row) as IndexOutcome.Completed
        assertEquals(gone.size, ok.swept)
        assertEquals("ONLINE", db.sourcesDao().get(row)!!.state)
        assertNull(meta.get(row).lastError)
    }

    @Test
    fun `a provider error closes the run and the next index walks afresh`() = runBlocking {
        val t = MemoryTree.generate(100)
        val row = addSource()
        t.failAt = "root/d2"
        t.failure = TreeFailure.ERROR
        val failed = indexer(t).index(row) as IndexOutcome.Failed
        assertEquals(com.fotoxplorr.app.sources.saf.SafHealth.ERROR, failed.health)
        assertEquals("ERROR", db.sourcesDao().get(row)!!.state)
        assertNull(db.indexRunsDao().latestRunning(row))
        assertEquals("FAILED", db.indexRunsDao().get(1)!!.status)
    }

    @Test
    fun `nothing is read while the grant is gone`() = runBlocking {
        val t = MemoryTree.generate(50)
        val row = addSource()
        grants.heldUris.clear()
        val outcome = indexer(t).index(row)
        assertEquals(IndexOutcome.Refused(com.fotoxplorr.app.sources.saf.SafHealth.REVOKED), outcome)
        assertEquals(0, t.listings)
        assertEquals("REVOKED", db.sourcesDao().get(row)!!.state)
        assertEquals(0, db.sourceAssetsDao().count(row))
    }

    @Test
    fun `an offline source that still does not answer reads nothing and stays offline`() = runBlocking {
        val t = MemoryTree.generate(50)
        val row = addSource(state = "OFFLINE")
        t.failAt = "root"
        val outcome = indexer(t).index(row)
        assertEquals(IndexOutcome.Refused(com.fotoxplorr.app.sources.saf.SafHealth.OFFLINE), outcome)
        assertEquals("OFFLINE", db.sourcesDao().get(row)!!.state)
        assertNull("no run was started", db.indexRunsDao().get(1))
        assertEquals(0, db.sourceAssetsDao().count(row))
    }

    @Test
    fun `an offline source that answers again is indexed and goes online`() = runBlocking {
        val t = MemoryTree.generate(50)
        val row = addSource(state = "OFFLINE")
        assertTrue(indexer(t).index(row) is IndexOutcome.Completed)
        assertEquals("ONLINE", db.sourcesDao().get(row)!!.state)
    }

    @Test
    fun `a revoked source whose grant is held again is indexed and goes online`() = runBlocking {
        val t = MemoryTree.generate(50)
        val row = addSource(state = "REVOKED")
        assertTrue(indexer(t).index(row) is IndexOutcome.Completed)
        assertEquals("ONLINE", db.sourcesDao().get(row)!!.state)
    }

    @Test
    fun `a walk that hit a cap is complete but never sweeps`() = runBlocking {
        val t = MemoryTree.generate(300)
        val row = addSource()
        indexer(t).index(row)
        val gone = t.dirs.getValue("root").filterNot { it.isDirectory }.take(3).map { it.documentId }.toSet()
        t.dirs["root"] = t.dirs.getValue("root").filterNot { it.documentId in gone }
        val capped = indexer(t, config = WalkConfig(maxDepth = 1)).index(row) as IndexOutcome.Completed
        assertFalse(capped.exhaustive)
        assertEquals(0, capped.swept)
        assertEquals(300, presentIds(row).size)
        assertFalse("a capped run is not recorded as a full one", db.indexRunsDao().get(2)!!.full)
    }

    @Test
    fun `cancelling keeps the checkpoint and the next index finishes`() = runBlocking {
        val t = MemoryTree.generate(2_000)
        val row = addSource()
        var cancelNow: () -> Unit = {}
        val job = async(Dispatchers.Default) { indexer(t, hook = { if (it == 2) cancelNow() }).index(row) }
        cancelNow = { job.cancel() }
        val outcome = try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { null }
        assertTrue("cancelled or already past the cancel point", outcome == null || outcome is IndexOutcome.Cancelled)
        val left = db.indexRunsDao().latestRunning(row)
        assertNotNull(left)
        assertTrue(db.sourceAssetsDao().count(row) in 1 until 2_000)
        val done = indexer(t).index(row) as IndexOutcome.Completed
        assertEquals(2_000L, done.files)
        assertEquals(2_000, presentIds(row).size)
    }

    @Test
    fun `a left-over run older than a week is closed instead of resumed`() = runBlocking {
        val t = MemoryTree.generate(60)
        val row = addSource()
        val stale = db.indexRunsDao().start(IndexRunRecord(sourceRow = row, status = "RUNNING", full = true, cursor = "wc1 junk", startedMs = 1))
        now = 1 + SourceIndexer.RESUME_WINDOW_MS + 10
        indexer(t).index(row)
        assertEquals("CANCELLED", db.indexRunsDao().get(stale)!!.status)
        assertEquals(60, presentIds(row).size)
    }

    @Test
    fun `an unreadable cursor starts the walk over`() = runBlocking {
        val t = MemoryTree.generate(60)
        val row = addSource()
        val bad = db.indexRunsDao().start(IndexRunRecord(sourceRow = row, status = "RUNNING", full = true, cursor = "not a cursor", startedMs = now))
        assertTrue(indexer(t).index(row) is IndexOutcome.Completed)
        assertEquals("CANCELLED", db.indexRunsDao().get(bad)!!.status)
        assertEquals(60, presentIds(row).size)
    }

    @Test
    fun `unreadable files are counted and kept and tried again up to the limit`() = runBlocking {
        val t = MemoryTree.generate(40)
        val row = addSource()
        val facts = FakeFacts()
        val victim = t.dirs.getValue("root").first { !it.isDirectory }.documentId
        facts.failFor += victim
        val first = indexer(t, facts).index(row) as IndexOutcome.Completed
        assertEquals(1, first.unreadable)
        assertEquals(39, first.sized)
        val rec = db.sourceAssetsDao().find(row, victim)!!
        assertEquals(2, rec.enrichState)
        assertEquals(1, rec.enrichAttempts)
        assertTrue("an unreadable file is still listed", rec.present)
        indexer(t, facts).index(row)
        indexer(t, facts).index(row)
        assertEquals(3, db.sourceAssetsDao().find(row, victim)!!.enrichAttempts)
        val calls = facts.calls
        indexer(t, facts).index(row)
        assertEquals("a file that failed three times is left alone", calls, facts.calls)
        assertEquals(3, db.sourceAssetsDao().find(row, victim)!!.enrichAttempts)
    }

    @Test
    fun `the sizing pass stops at its byte cap and leaves the rest for later`() = runBlocking {
        val t = MemoryTree.generate(200)
        val row = addSource()
        val facts = FakeFacts(bytesEach = 1_000)
        val cap = EnrichConfig(localAuthorities = setOf("auth"), chunk = 20, maxBytes = 50_000)
        val first = indexer(t, facts, enrich = cap).index(row) as IndexOutcome.Completed
        assertTrue("sized ${first.sized}", first.sized in 50..100)
        assertEquals(200 - first.sized, first.deferred)
        val pending = db.sourceAssetsDao().forSource(row).count { it.enrichState == 0 }
        assertEquals(first.deferred, pending)
        indexer(t, facts, enrich = cap).index(row)
        assertTrue(db.sourceAssetsDao().forSource(row).count { it.enrichState == 1 } > first.sized)
    }

    @Test
    fun `a cloud-backed provider is listed but its files are never opened for sizing`() = runBlocking {
        val t = MemoryTree.generate(30)
        val cloud = "content://com.example.cloud/tree/abc"
        grants.heldUris += cloud
        val row = addSource(locator = cloud)
        val facts = FakeFacts()
        val outcome = indexer(t, facts, enrich = EnrichConfig()).index(row) as IndexOutcome.Completed
        assertEquals(30L, outcome.files)
        assertEquals(0, facts.calls)
        assertEquals(30, db.sourceAssetsDao().forSource(row).count { it.enrichState == 0 })
    }

    @Test
    fun `a skipped file is neither sized nor counted unreadable`() = runBlocking {
        val t = MemoryTree.generate(10)
        val row = addSource()
        val outcome = indexer(t, FakeFacts(outcome = EnrichmentOutcome.SKIPPED)).index(row) as IndexOutcome.Completed
        assertEquals(0, outcome.sized)
        assertEquals(0, outcome.unreadable)
    }

    @Test
    fun `an exception inside a commit rolls the whole slice back`() = runBlocking {
        val t = MemoryTree.generate(100)
        val row = addSource()
        var committed = 0
        val broken = SourceIndexer(
            db = db, grants = grants, treeFor = { t },
            documentUri = { _, id -> if (committed++ == 30) error("disk full") else "content://auth/document/$id" },
            facts = FakeFacts().reader, meta = meta, sliceBudget = WalkBudget(maxDirectories = 1000),
        )
        try { broken.index(row); error("should have failed") } catch (e: IllegalStateException) { assertEquals("disk full", e.message) }
        assertEquals("the files of the slice and its checkpoint went together", 0, db.sourceAssetsDao().count(row))
        assertNull(db.indexRunsDao().get(1)!!.cursor)
    }

    @Test
    fun `an index of a source that is not there says so`() = runBlocking {
        assertEquals(IndexOutcome.Missing, indexer(MemoryTree.generate(5)).index(99))
        assertNotEquals(IndexOutcome.Busy, IndexOutcome.Missing)
    }

    @Test
    fun `blocked sizing can be cancelled without losing the walk`() = runBlocking {
        val t = MemoryTree.generate(100)
        val row = addSource()
        val gate = CompletableDeferred<Unit>()
        val facts = FakeFacts()
        val slowReader = FactsReader { s, a -> gate.await(); facts.reader.read(s, a) }
        val job = async(Dispatchers.Default) {
            SourceIndexer(db, grants, { t }, { _, id -> "content://auth/document/$id" }, slowReader, meta,
                enrichConfig = EnrichConfig(localAuthorities = setOf("auth"))).index(row)
        }
        while (db.indexRunsDao().get(1)?.status != "COMPLETED") kotlinx.coroutines.delay(10)
        job.cancel()
        try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { }
        assertEquals(100, presentIds(row).size)
        assertNull("the walk finished before sizing, so there is nothing to resume", db.indexRunsDao().latestRunning(row))
    }
}
