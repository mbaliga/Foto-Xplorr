package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a relaunch makes of the regions the library kept: paused and resumable, never started. */
class OfflineRegionReconcileTest {

    private val box = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!

    private fun record(name: String, used: Long = 1L, created: Long = 1L, bytes: Long = 5_000_000L) = RegionRecord(
        name = name, box = box, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = created, lastUsedMillis = used, bytes = bytes, tileCount = 90,
    )

    private fun progress(done: Long, complete: Boolean = false) = RegionProgress(
        completedResources = done, requiredResources = 100, requiredIsPrecise = true,
        completedBytes = done * 1000, completedTiles = done / 2, complete = complete,
    )

    private fun region(id: Long, rec: RegionRecord, p: RegionProgress, running: Boolean = false) =
        StoredRegion(id, rec, p, running)

    @Test
    fun `a half-done region becomes a paused entry that says restarted`() {
        val half = region(1, record("Paris"), progress(40))
        val event = OfflineRegionReconcile.restoredEvent(half)!!

        val moved = DownloadGuard.reduce(DownloadState.Idle, event)

        val paused = (moved as GuardResult.Moved).state as DownloadState.Paused
        assertEquals(PauseReason.RESTARTED, paused.reason)
        assertEquals(40L, paused.progress.completedResources)
        assertEquals(half.record.tileCount, paused.estimate.tileCount)
        assertEquals(half.record.bytes, paused.estimate.bytes)
        assertEquals(box, paused.estimate.box)
        assertTrue(DownloadGuard.canResume(paused, throttleInstalled = true))
        assertTrue(!DownloadGuard.canResume(paused, throttleInstalled = false))
    }

    @Test
    fun `a finished region and a running one are not restored`() {
        assertNull(OfflineRegionReconcile.restoredEvent(region(1, record("A"), progress(100, complete = true))))
        assertNull(OfflineRegionReconcile.restoredEvent(region(2, record("B"), progress(10), running = true)))
    }

    @Test
    fun `a restored event never leads to downloading`() {
        val event = OfflineRegionReconcile.restoredEvent(region(1, record("A"), progress(10)))!!
        val state = (DownloadGuard.reduce(DownloadState.Idle, event) as GuardResult.Moved).state
        assertTrue(state !is DownloadState.Downloading)
    }

    @Test
    fun `unfinished and finished are split and ordered least recently used first`() {
        val regions = listOf(
            region(1, record("New", used = 30), progress(10)),
            region(2, record("Old", used = 5), progress(10)),
            region(3, record("DoneRecent", used = 40), progress(100, complete = true)),
            region(4, record("DoneOld", used = 2), progress(100, complete = true)),
        )

        assertEquals(listOf("Old", "New"), OfflineRegionReconcile.unfinished(regions).map { it.first.record.name })
        assertEquals(listOf("DoneOld", "DoneRecent"), OfflineRegionReconcile.finished(regions).map { it.record.name })
    }

    @Test
    fun `reconcile deletes nothing and has no engine to call`() {
        // Pure functions over a list: the only way to delete is the engine, which this object never sees.
        val regions = listOf(region(1, record("A"), progress(10)))
        OfflineRegionReconcile.unfinished(regions)
        OfflineRegionReconcile.finished(regions)
        assertEquals(1, regions.size)
    }
}
