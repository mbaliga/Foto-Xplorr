package com.fotoxplorr.core.db.sources

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FotozSourcesDatabaseTest {

    private lateinit var db: FotozSourcesDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozSourcesDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun newSource(locator: String = "content://tree/1"): Long =
        db.sourcesDao().insert(
            SourceRecord(kind = "SAF_TREE", rootLocator = locator, volumeUuid = null, displayName = "Photos", state = "ONLINE", createdMs = 1),
        )

    private fun asset(source: Long, doc: String, run: Long, size: Long = 10) = SourceAssetRecord(
        sourceRow = source, documentId = doc, displayName = "$doc.jpg", mime = "image/jpeg", sizeBytes = size,
        path = "DCIM", contentUri = "content://x/$doc", lastSeenRun = run,
    )

    private suspend fun upsert(a: SourceAssetRecord) {
        val dao = db.sourceAssetsDao()
        if (dao.insertIgnore(a) == -1L) {
            dao.refresh(a.sourceRow, a.documentId, a.displayName, a.mime, a.sizeBytes, a.dateModifiedMs, a.path, a.contentUri, a.lastSeenRun)
        }
    }

    private suspend fun fullRun(source: Long, status: String): Long {
        val id = db.indexRunsDao().start(IndexRunRecord(sourceRow = source, status = "RUNNING", full = true, cursor = null, startedMs = 1))
        if (status != "RUNNING") db.indexRunsDao().finish(id, status, 2, null)
        return id
    }

    @Test
    fun `asset row numbers are unique and never reused after a delete`() = runBlocking {
        val s = newSource()
        val rows = (1..500).map { db.sourceAssetsDao().insertIgnore(asset(s, "d$it", 1)) }
        assertEquals(rows.size, rows.toSet().size)
        assertTrue(rows.all { it >= 1 })
        // Remove the whole source: AUTOINCREMENT must keep counting upward.
        db.sourcesDao().remove(s)
        val s2 = newSource("content://tree/2")
        val next = db.sourceAssetsDao().insertIgnore(asset(s2, "again", 1))
        assertTrue(next > rows.max(), "row $next reused a number at or below ${rows.max()}")
        assertTrue(s2 > s)
    }

    @Test
    fun `refreshing an existing document keeps its row number and enrichment`() = runBlocking {
        val s = newSource()
        upsert(asset(s, "a", 1))
        val before = db.sourceAssetsDao().find(s, "a")!!
        db.sourceAssetsDao().setEnriched(before.assetRow, 640, 480, 0, 123)
        upsert(asset(s, "a", 2, size = 99))
        val after = db.sourceAssetsDao().find(s, "a")!!
        assertEquals(before.assetRow, after.assetRow)
        assertEquals(99, after.sizeBytes)
        assertEquals(640, after.width)
        assertEquals(1, after.enrichState)
        assertEquals(2, after.lastSeenRun)
        assertEquals(1, db.sourceAssetsDao().count(s))
    }

    @Test
    fun `ids stay stable after the file is reopened`() = runBlocking {
        val file = File.createTempFile("fotoz-sources", ".db")
        file.delete()
        try {
            fun open() = Room.databaseBuilder<FotozSourcesDatabase>(file.absolutePath)
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
            val first = open()
            val s = first.sourcesDao().insert(SourceRecord(kind = "SAF_TREE", rootLocator = "t", volumeUuid = null, displayName = "n", state = "ONLINE", createdMs = 1))
            val a = first.sourceAssetsDao().insertIgnore(asset(s, "a", 1))
            first.close()
            val second = open()
            assertEquals(a, second.sourceAssetsDao().find(s, "a")!!.assetRow)
            val b = second.sourceAssetsDao().insertIgnore(asset(s, "b", 1))
            assertTrue(b > a)
            second.close()
        } finally {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    @Test
    fun `offline and revoked states delete nothing`() = runBlocking {
        val s = newSource()
        upsert(asset(s, "a", 1))
        upsert(asset(s, "b", 1))
        for (state in listOf("OFFLINE", "REVOKED", "ERROR", "ONLINE")) {
            db.sourcesDao().setState(s, state)
            assertEquals(2, db.sourceAssetsDao().count(s), state)
            assertEquals(state, db.sourcesDao().get(s)!!.state)
        }
    }

    @Test
    fun `sweep marks unseen assets only for a completed full run of an online source`() = runBlocking {
        val s = newSource()
        upsert(asset(s, "keep", 1))
        upsert(asset(s, "gone", 1))
        val dao = db.sourceAssetsDao()

        // A walk that is still running, failed or cancelled never sweeps.
        for (status in listOf("RUNNING", "FAILED", "CANCELLED")) {
            val run = fullRun(s, status)
            upsert(asset(s, "keep", run))
            assertEquals(0, dao.sweepMissing(s, run), status)
            assertEquals(2, dao.forSource(s).count { it.present }, status)
        }

        // A completed walk of a source that is not ONLINE never sweeps either.
        val run = fullRun(s, "COMPLETED")
        upsert(asset(s, "keep", run))
        for (state in listOf("OFFLINE", "REVOKED", "ERROR")) {
            db.sourcesDao().setState(s, state)
            assertEquals(0, dao.sweepMissing(s, run), state)
        }
        db.sourcesDao().setState(s, "ONLINE")

        // A completed but partial (delta) run is not a full extent.
        val partial = db.indexRunsDao().start(IndexRunRecord(sourceRow = s, status = "RUNNING", full = false, cursor = null, startedMs = 1))
        db.indexRunsDao().finish(partial, "COMPLETED", 2, null)
        assertEquals(0, dao.sweepMissing(s, partial))

        // A run id of another source is no authority.
        val other = newSource("content://tree/other")
        assertEquals(0, dao.sweepMissing(other, run))

        assertEquals(1, dao.sweepMissing(s, run))
        val rows = dao.forSource(s)
        assertEquals(2, rows.size, "sweep must not delete rows")
        assertEquals(listOf("keep"), rows.filter { it.present }.map { it.documentId })
        // Seen again later: present is restored and the row number is the same.
        val goneRow = rows.first { it.documentId == "gone" }.assetRow
        upsert(asset(s, "gone", run + 10))
        assertTrue(dao.find(s, "gone")!!.present)
        assertEquals(goneRow, dao.find(s, "gone")!!.assetRow)
    }

    @Test
    fun `cursor checkpoints persist and a running run is resumable`() = runBlocking {
        val s = newSource()
        val runs = db.indexRunsDao()
        val run = runs.start(IndexRunRecord(sourceRow = s, status = "RUNNING", full = true, cursor = null, startedMs = 5))
        runs.checkpoint(run, "wc1\n10\n3\n-\n0", 10)
        runs.checkpoint(run, "wc1\n20\n0\n+d\n1\nq", 20)
        val resumed = runs.latestRunning(s)
        assertNotNull(resumed)
        assertEquals(run, resumed.runId)
        assertEquals("wc1\n20\n0\n+d\n1\nq", resumed.cursor)
        assertEquals(20, resumed.filesSeen)

        runs.finish(run, "FAILED", 9, "boom")
        assertNull(runs.latestRunning(s))
        // A finished run's cursor is not overwritten by a late checkpoint.
        runs.checkpoint(run, "late", 99)
        assertEquals("wc1\n20\n0\n+d\n1\nq", runs.get(run)!!.cursor)
        assertEquals("boom", runs.get(run)!!.error)
    }

    @Test
    fun `source locator is unique and removing a source cascades`() = runBlocking {
        val s = newSource("same")
        assertEquals(s, db.sourcesDao().findByLocator("SAF_TREE", "same")!!.sourceRow)
        val failed = runCatching { newSource("same") }.isFailure
        assertTrue(failed)
        db.sourceFoldersDao().insertIgnore(SourceFolderRecord(sourceRow = s, documentId = "root", path = ""))
        upsert(asset(s, "a", 1))
        db.sourcesDao().remove(s)
        assertEquals(0, db.sourceAssetsDao().count(s))
        assertEquals(emptyList(), db.sourceFoldersDao().forSource(s))
    }

    @Test
    fun `unreadable enrichment is counted not dropped`() = runBlocking {
        val s = newSource()
        upsert(asset(s, "a", 1))
        val row = db.sourceAssetsDao().find(s, "a")!!.assetRow
        db.sourceAssetsDao().setUnreadable(row)
        db.sourceAssetsDao().setUnreadable(row)
        val r = db.sourceAssetsDao().get(row)!!
        assertEquals(2, r.enrichState)
        assertEquals(2, r.enrichAttempts)
        assertTrue(r.present)
    }
}
