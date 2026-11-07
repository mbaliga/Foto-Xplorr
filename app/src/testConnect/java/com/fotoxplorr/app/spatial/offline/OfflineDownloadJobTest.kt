package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download job against the shared fake engine: the guard drives the engine, cancel is pause,
 * nothing starts by itself, and the limits are enforced where the region is created.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloadJobTest {

    private val box = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val estimate = (RegionBudget.plan(box) as BudgetVerdict.Fits).estimate
    private val record = RegionRecord(
        name = "Paris", box = box, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1L, lastUsedMillis = 1L, bytes = estimate.bytes, tileCount = estimate.tileCount,
    )
    private val downloading = DownloadState.Downloading(estimate, RegionProgress.EMPTY)

    private fun reading(
        done: Long,
        error: DownloadErrorKind? = null,
        complete: Boolean = false,
        bytes: Long = done * 100_000,
        tiles: Long = done / 2,
    ) = RegionProgress(
        completedResources = done, requiredResources = 100, requiredIsPrecise = true,
        completedBytes = bytes, completedTiles = tiles, complete = complete, error = error,
    )

    private class Run(val end: Deferred<DownloadEnd>, val states: MutableList<DownloadState>, val fractions: MutableList<Float?>)

    private fun TestScope.start(
        engine: OfflineRegionEngine,
        target: DownloadTarget,
        from: DownloadState.Downloading = downloading,
    ): Run {
        val states = mutableListOf<DownloadState>()
        val fractions = mutableListOf<Float?>()
        val end = async(UnconfinedTestDispatcher(testScheduler)) {
            OfflineDownloadJob.run(engine, target, from, nowMillis = { 777L }, onState = { states += it }, onProgress = { fractions += it })
        }
        return Run(end, states, fractions)
    }

    private suspend fun FakeOfflineRegionEngine.stored(rec: RegionRecord = record) =
        (create(rec) as EngineResult.Ok).value

    // ---- starting ----

    @Test
    fun `a new area is checked, created paused, then resumed, in that order`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val run = start(engine, DownloadTarget.New("Paris", estimate))

        assertEquals(listOf("list", "create", "resume:1"), engine.calls)
        assertTrue(engine.isRunning(1))
        val created = (engine.list() as EngineResult.Ok).value.single().record
        assertEquals("Paris", created.name)
        assertEquals(box, created.box)
        assertEquals(estimate.maxZoom, created.maxZoom)
        assertEquals("liberty", created.styleId)
        assertEquals(777L, created.createdAtMillis)
        assertEquals(estimate.bytes, created.bytes)

        run.end.cancel()
    }

    @Test
    fun `resuming a stored region creates nothing`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        engine.calls.clear()

        val run = start(engine, DownloadTarget.Existing(region))

        assertEquals(listOf("resume:${region.id}"), engine.calls)
        run.end.cancel()
    }

    @Test
    fun `nothing is started until the job runs`() = runTest {
        val engine = FakeOfflineRegionEngine()
        engine.stored()
        // Listing and even building the target are not a start.
        engine.list()
        assertFalse(engine.calls.any { it.startsWith("resume") })
    }

    // ---- ends ----

    @Test
    fun `a finished download is done and the engine is not asked to pause it`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        engine.emit(region.id, reading(50))
        engine.emit(region.id, reading(100, complete = true))
        val end = run.end.await()

        assertTrue(end.state is DownloadState.Done)
        assertEquals(region.id, end.regionId)
        assertFalse(engine.calls.any { it.startsWith("pause") || it.startsWith("delete") })
        assertEquals(1f, run.fractions.last()!!, 0f)
        assertEquals(0.5f, run.fractions.first { it != null && it > 0.4f }!!, 0.001f)
    }

    @Test
    fun `cancel is a pause that keeps the region, reported as the person's pause`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))
        engine.emit(region.id, reading(30))

        run.end.cancel()
        val cancelled = runCatching { run.end.await() }.exceptionOrNull()

        assertTrue(cancelled is CancellationException)
        val last = run.states.last() as DownloadState.Paused
        assertEquals(PauseReason.USER, last.reason)
        assertEquals(30L, last.progress.completedResources)
        assertTrue(engine.calls.contains("pause:${region.id}"))
        assertFalse(engine.calls.any { it.startsWith("delete") })
        assertFalse(engine.isRunning(region.id))
        assertEquals(1, (engine.list() as EngineResult.Ok).value.size)
    }

    @Test
    fun `a size over the cap stops the download and the engine`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        engine.emit(region.id, reading(60, bytes = OfflineMapLimits.MAX_BYTES + 1))
        val end = run.end.await()

        assertEquals(FailureReason.OVER_CAP, (end.state as DownloadState.Failed).reason)
        assertTrue(engine.calls.contains("pause:${region.id}"))
        assertFalse(engine.isRunning(region.id))
    }

    @Test
    fun `repeated connection errors pause the download and say so without an error dialog`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS) {
            engine.emit(region.id, reading(10, error = DownloadErrorKind.CONNECTION))
        }
        val end = run.end.await()

        assertEquals(PauseReason.CONNECTION, (end.state as DownloadState.Paused).reason)
        assertTrue(engine.calls.contains("pause:${region.id}"))
        assertEquals(Result.success("Paused, no connection."), OfflineDownloadJob.outcomeOf(end, "Paris"))
    }

    @Test
    fun `repeated server errors fail the download with a message`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS) {
            engine.emit(region.id, reading(10, error = DownloadErrorKind.SERVER))
        }
        val end = run.end.await()

        assertEquals(FailureReason.SERVER, (end.state as DownloadState.Failed).reason)
        val failure = OfflineDownloadJob.outcomeOf(end, "Paris").exceptionOrNull()
        assertEquals("The map provider returned errors.", failure?.message)
    }

    @Test
    fun `a missing tile at the edge of the data is not a reason to stop`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        repeat(20) { engine.emit(region.id, reading(10, error = DownloadErrorKind.NOT_FOUND)) }

        assertTrue(run.states.last() is DownloadState.Downloading)
        run.end.cancel()
    }

    @Test
    fun `a region deleted while downloading fails the job`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        engine.delete(region.id)
        val end = run.end.await()

        assertEquals(FailureReason.ENGINE, (end.state as DownloadState.Failed).reason)
    }

    @Test
    fun `a reading after the pause changes nothing`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))
        repeat(OfflineMapLimits.MAX_CONSECUTIVE_ERRORS) {
            engine.emit(region.id, reading(10, error = DownloadErrorKind.CONNECTION))
        }
        run.end.await()
        val before = run.states.toList()

        engine.emit(region.id, reading(90))

        assertEquals(before, run.states)
    }

    // ---- refusals ----

    @Test
    fun `without the request limit the engine refuses, nothing is fetched and the person is told`() = runTest {
        val engine = FakeOfflineRegionEngine(throttleInstalled = false)
        val region = engine.stored()
        val run = start(engine, DownloadTarget.Existing(region))

        val end = run.end.await()

        assertEquals(EngineFailure.THROTTLE_NOT_INSTALLED, end.refused)
        assertTrue(end.state is DownloadState.Paused)
        assertFalse(engine.isRunning(region.id))
        val failure = OfflineDownloadJob.outcomeOf(end, "Paris").exceptionOrNull()
        assertEquals(OfflineMapsCopy.UNAVAILABLE, failure?.message)
    }

    @Test
    fun `a seventh region is refused and nothing is created or removed`() = runTest {
        val engine = FakeOfflineRegionEngine()
        repeat(OfflineMapLimits.MAX_REGIONS) { engine.stored(record.copy(name = "Area $it")) }
        engine.calls.clear()

        val run = start(engine, DownloadTarget.New("One more", estimate))
        val end = run.end.await()

        assertTrue(end.noRoom is RoomVerdict.TooManyRegions)
        assertNull(end.regionId)
        assertTrue(end.state is DownloadState.Failed)
        assertEquals(listOf("list"), engine.calls)
        assertEquals(OfflineMapLimits.MAX_REGIONS, (engine.list() as EngineResult.Ok).value.size)
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, OfflineDownloadJob.outcomeOf(end, "One more").exceptionOrNull()?.message)
    }

    @Test
    fun `passing the total size is refused too, and never by removing another region`() = runTest {
        val engine = FakeOfflineRegionEngine()
        engine.stored(record.copy(name = "Big", bytes = OfflineMapLimits.MAX_TOTAL_BYTES))
        engine.calls.clear()

        val end = start(engine, DownloadTarget.New("More", estimate)).end.await()

        assertTrue(end.noRoom is RoomVerdict.OverTotalBytes)
        assertFalse(engine.calls.any { it.startsWith("delete") || it.startsWith("create") })
    }

    @Test
    fun `an engine that cannot list fails the job before anything is created`() = runTest {
        val end = start(object : OfflineRegionEngine by FakeOfflineRegionEngine() {
            override suspend fun list(): EngineResult<List<StoredRegion>> =
                EngineResult.Failure(EngineFailure.ENGINE_ERROR, "no")
        }, DownloadTarget.New("Paris", estimate)).end.await()

        assertEquals(EngineFailure.ENGINE_ERROR, end.refused)
        assertNull(end.regionId)
        assertTrue(end.state is DownloadState.Failed)
    }

    // ---- through the job runner ----

    @Test
    fun `as a job it shows as a map download with its progress, and finishes with a notice`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val runner = JobRunner(this)
        val states = mutableListOf<DownloadState>()

        OfflineDownloadJob.launch(runner, engine, DownloadTarget.Existing(region), downloading, { 1L }) { states += it }
        advanceUntilIdle()

        val job = runner.jobs.value.single()
        assertEquals(ActivityKind.DOWNLOADING_MAP, job.kind)
        assertEquals("Downloading Paris", job.title)

        engine.emit(region.id, reading(25))
        advanceUntilIdle()
        assertEquals(0.25f, runner.jobs.value.single().progress!!, 0.001f)

        val outcome = async(UnconfinedTestDispatcher(testScheduler)) { runner.outcomes.first() }
        engine.emit(region.id, reading(100, complete = true))
        advanceUntilIdle()

        assertTrue(runner.jobs.value.isEmpty())
        assertEquals(Result.success("Saved Paris for offline use."), outcome.await().result)
        assertTrue(states.last() is DownloadState.Done)
    }

    @Test
    fun `cancelling the job pauses the region and ends as a silent cancellation`() = runTest {
        val engine = FakeOfflineRegionEngine()
        val region = engine.stored()
        val runner = JobRunner(this)
        val states = mutableListOf<DownloadState>()
        val handle = OfflineDownloadJob.launch(runner, engine, DownloadTarget.Existing(region), downloading, { 1L }) { states += it }
        advanceUntilIdle()
        engine.emit(region.id, reading(40))
        advanceUntilIdle()
        val outcome = async(UnconfinedTestDispatcher(testScheduler)) { runner.outcomes.first() }

        handle.cancel()
        advanceUntilIdle()

        assertTrue(runner.jobs.value.isEmpty())
        assertTrue(outcome.await().result.exceptionOrNull() is CancellationException)
        assertEquals(PauseReason.USER, (states.last() as DownloadState.Paused).reason)
        assertTrue(engine.calls.contains("pause:${region.id}"))
        assertFalse(engine.calls.any { it.startsWith("delete") })
        assertNotNull(engine.progress(region.id).first())
    }

    // ---- outcome mapping and copy ----

    @Test
    fun `a pause by the person has no notice and no error`() {
        val paused = DownloadEnd(DownloadState.Paused(estimate, RegionProgress.EMPTY, PauseReason.USER), regionId = 1L)
        assertEquals(Result.success(""), OfflineDownloadJob.outcomeOf(paused, "Paris"))
    }

    @Test
    fun `the job's own sentences are short`() {
        val title = OfflineJobCopy.title("Paris")
        val saved = OfflineJobCopy.saved("Paris")
        assertTrue(title, title.length <= 40 && !title.contains('.'))
        assertTrue(saved, saved.length <= 60 && saved.endsWith(".") && saved.count { it == '.' } == 1)
    }
}
