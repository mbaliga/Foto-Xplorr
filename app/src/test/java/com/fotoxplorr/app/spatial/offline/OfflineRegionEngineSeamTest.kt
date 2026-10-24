package com.fotoxplorr.app.spatial.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of [OfflineRegionEngine], proved on the fake, and the guard driving the engine the
 * way the Connect code is meant to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRegionEngineSeamTest {

    private val box = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val estimate = (RegionBudget.plan(box) as BudgetVerdict.Fits).estimate
    private val record = RegionRecord(
        name = "Paris", box = box, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1L, lastUsedMillis = 1L, bytes = estimate.bytes, tileCount = estimate.tileCount,
    )

    private fun progress(done: Long, error: DownloadErrorKind? = null, complete: Boolean = false) = RegionProgress(
        completedResources = done, requiredResources = 100, requiredIsPrecise = true,
        completedBytes = done * 100_000, completedTiles = done / 2, complete = complete, error = error,
    )

    private suspend fun FakeOfflineRegionEngine.createOne(): Long =
        (create(record) as EngineResult.Ok).value.id

    // ---- the contract ----

    @Test
    fun `create stores a paused region and fetches nothing`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val id = engine.createOne()
        assertFalse(engine.isRunning(id))
        assertEquals(listOf("create"), engine.calls)
        val listed = (engine.list() as EngineResult.Ok).value
        assertEquals(listOf(record), listed.map { it.record })
        assertFalse(listed.single().running)
    }

    @Test
    fun `listing and watching progress never start a download`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val id = engine.createOne()
        engine.list()
        val seen = mutableListOf<RegionProgress>()
        val job = launch { engine.progress(id).toList(seen) }
        advanceUntilIdle()
        job.cancel()
        assertTrue(engine.calls.none { it.startsWith("resume") })
        assertFalse(engine.isRunning(id))
    }

    @Test
    fun `resume is refused and fetches nothing when the throttle is not installed`() = runTest {
        val engine = FakeOfflineRegionEngine(throttleInstalled = false)
        val id = engine.createOne()
        val result = engine.resume(id)
        assertEquals(EngineResult.Failure(EngineFailure.THROTTLE_NOT_INSTALLED), result)
        assertFalse(engine.isRunning(id))
    }

    @Test
    fun `pause keeps the region and what it fetched`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val id = engine.createOne()
        engine.resume(id)
        engine.emit(id, progress(40))
        engine.pause(id)
        val region = (engine.list() as EngineResult.Ok).value.single()
        assertFalse(region.running)
        assertEquals(40L, region.progress.completedResources)
        assertTrue(engine.calls.none { it.startsWith("delete") })
    }

    @Test
    fun `delete removes the region and ends its progress feed`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val id = engine.createOne()
        val seen = mutableListOf<RegionProgress>()
        val job = launch { engine.progress(id).toList(seen) }
        advanceUntilIdle()
        engine.emit(id, progress(10))
        advanceUntilIdle()
        engine.delete(id)
        advanceUntilIdle()
        assertTrue("the feed should have ended", job.isCompleted)
        assertEquals(listOf(0L, 10L), seen.map { it.completedResources })
        assertEquals(emptyList<StoredRegion>(), (engine.list() as EngineResult.Ok).value)
    }

    @Test
    fun `calls on an unknown region say so`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val missing = EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        assertEquals(missing, engine.resume(99))
        assertEquals(missing, engine.pause(99))
        assertEquals(missing, engine.delete(99))
        assertEquals(emptyList<RegionProgress>(), engine.progress(99).toList())
    }

    // ---- the guard driving the engine ----

    @Test
    fun `a tap runs the guard then the engine and cancel pauses without deleting`() = runTest {
        val engine = FakeOfflineRegionEngine()
        var state: DownloadState = DownloadState.Ready(estimate)

        // Download tapped: only now does anything reach the engine.
        state = DownloadGuard.reduce(state, DownloadEvent.Start(engine.throttleInstalled, metered = false)).state
        assertTrue(state is DownloadState.Downloading)
        val id = engine.createOne()
        assertTrue(engine.resume(id) is EngineResult.Ok)
        assertTrue(engine.isRunning(id))

        engine.emit(id, progress(30))
        state = DownloadGuard.reduce(state, DownloadEvent.Progress(progress(30))).state
        assertEquals(30L, (state as DownloadState.Downloading).progress.completedResources)

        // Cancel: the guard says Paused, so the glue pauses the engine and leaves the data alone.
        state = DownloadGuard.reduce(state, DownloadEvent.Cancel).state
        assertTrue(state is DownloadState.Paused)
        engine.pause(id)
        assertFalse(engine.isRunning(id))
        assertEquals(1, (engine.list() as EngineResult.Ok).value.size)
        assertTrue(engine.calls.none { it.startsWith("delete") })

        // A late reading after the pause is refused and harmless.
        val late = DownloadGuard.reduce(state, DownloadEvent.Progress(progress(31)))
        assertTrue(late is GuardResult.Rejected)
    }

    @Test
    fun `without the throttle the guard refuses and the engine is never asked`() = runTest {
        val engine = FakeOfflineRegionEngine(throttleInstalled = false)
        val state = DownloadState.Ready(estimate)
        val result = DownloadGuard.reduce(state, DownloadEvent.Start(engine.throttleInstalled, metered = false))
        assertTrue(result is GuardResult.Rejected)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `after a relaunch a stored half finished region lists as stopped and a tap on resume is the only way back`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val id = engine.createOne()
        engine.emit(id, progress(55))
        val stored = (engine.list() as EngineResult.Ok).value.single()
        assertFalse(stored.running)
        assertEquals(55L, stored.progress.completedResources)
        assertTrue(engine.calls.none { it.startsWith("resume") })

        // What the pane does with it: no recorded reason reads as RESTARTED, and only Resume moves it.
        val step = PaneDownloadFlow.tapResume(
            stored.record, stored.progress, PauseReason.RESTARTED, engine.throttleInstalled, metered = false,
        )
        assertTrue(step is PaneStep.Launch)
        assertEquals(55L, (step as PaneStep.Launch).from.progress.completedResources)
    }
}
