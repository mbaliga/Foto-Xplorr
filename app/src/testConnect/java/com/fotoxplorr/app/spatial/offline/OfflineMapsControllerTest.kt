package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane's behaviour against the shared fake engine and a real job runner: opening lists and
 * starts nothing, a download starts only from the tap (or the accepted mobile-data question), pause
 * keeps the area, and delete removes only what the confirmation named.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsControllerTest {

    private val paris = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val parisOption = OfflineMapsPaneModel.options(emptyList(), paris).single()
    private val tooBig = OfflineMapsPaneModel.options(emptyList(), GeoBox.clamped(5.96, 45.82, 10.49, 47.81)!!).single()

    private fun record(name: String = "Paris", lastUsed: Long = 1L) = RegionRecord(
        name = name, box = paris, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1L, lastUsedMillis = lastUsed, bytes = 12_000_000L, tileCount = 100L,
    )

    private fun reading(done: Long, complete: Boolean = false) = RegionProgress(
        completedResources = done, requiredResources = 100, requiredIsPrecise = true,
        completedBytes = done * 100_000, completedTiles = done / 2, complete = complete,
    )

    private class Rig(
        val engine: FakeOfflineRegionEngine,
        val runner: JobRunner,
        val controller: OfflineMapsController,
        val scope: CoroutineScope,
        var metered: Boolean,
        val scopeOf: (FakeOfflineRegionEngine) -> OfflineMapsController,
    )

    private fun TestScope.rig(metered: Boolean = false, throttle: Boolean = true): Rig {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val engine = FakeOfflineRegionEngine(throttleInstalled = throttle)
        val runner = JobRunner(scope)
        lateinit var rig: Rig
        val make = { e: FakeOfflineRegionEngine ->
            OfflineMapsController(e, runner, MeteredCheck { rig.metered }, nowMillis = { 777L }, scope = scope)
        }
        rig = Rig(engine, runner, make(engine), scope, metered, make)
        return rig
    }

    private suspend fun FakeOfflineRegionEngine.stored(rec: RegionRecord = record()) = (create(rec) as EngineResult.Ok).value

    // ---- opening ----

    @Test
    fun `opening lists the stored areas and starts nothing`() = runTest {
        val rig = rig()
        rig.engine.stored()
        rig.engine.calls.clear()

        rig.controller.open()

        assertEquals(listOf("list"), rig.engine.calls)
        assertTrue(rig.controller.state.value.loaded)
        assertEquals(1, rig.controller.state.value.regions.size)
        assertEquals(0, rig.runner.jobs.value.size)
        rig.scope.cancel()
    }

    @Test
    fun `an area left half done by a relaunch comes back paused and is not resumed`() = runTest {
        val rig = rig()
        val region = rig.engine.stored()
        rig.engine.emit(region.id, reading(40))
        rig.engine.calls.clear()
        rig.controller.open()

        val rows = OfflineMapsPaneModel.savedRows(
            rig.controller.state.value.regions, rig.controller.state.value.pauseReasons,
            rig.controller.state.value.live, rig.controller.env(),
        )
        assertEquals(RowStatus.PAUSED, rows.single().status)
        assertEquals(OfflineMapsCopy.paused(PauseReason.RESTARTED), rows.single().statusLine)
        assertFalse(rig.engine.calls.any { it.startsWith("resume") || it.startsWith("create") })
        rig.scope.cancel()
    }

    @Test
    fun `a list that fails is said so and the pane stays usable`() = runTest {
        val failing = object : OfflineRegionEngine by FakeOfflineRegionEngine() {
            override suspend fun list(): EngineResult<List<StoredRegion>> = EngineResult.Failure(EngineFailure.ENGINE_ERROR)
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val controller = OfflineMapsController(failing, JobRunner(scope), MeteredCheck { false }, { 0L }, scope)
        controller.open()
        assertTrue(controller.state.value.listFailed)
        assertTrue(controller.state.value.loaded)
        scope.cancel()
    }

    // ---- download ----

    @Test
    fun `a tap on Download on Wi-Fi creates, then resumes, and the area is listed as downloading`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.download(parisOption, "My Paris")

        assertEquals(listOf("list", "create", "resume:1", "list"), rig.engine.calls)
        val state = rig.controller.state.value
        assertEquals("My Paris", state.regions.single().record.name)
        assertTrue(state.regions.single().running)
        assertEquals(1, state.jobsRunning)
        assertTrue(state.downloadRunning)
        rig.scope.cancel()
    }

    @Test
    fun `while one downloads the other buttons say one at a time`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)

        val other = OfflineMapsPaneModel.button(parisOption, rig.controller.env())
        assertFalse(other.enabled)
        assertEquals(OfflineMapsCopy.ONE_AT_A_TIME, other.note)

        val before = rig.engine.calls.size
        rig.controller.download(parisOption, null)
        assertEquals(before, rig.engine.calls.size)
        assertEquals(OfflineMapsCopy.ONE_AT_A_TIME, rig.controller.state.value.notice)
        rig.scope.cancel()
    }

    @Test
    fun `a second tap in a row creates nothing more`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, "One")
        rig.controller.download(parisOption, "Two")

        assertEquals(1, rig.engine.calls.count { it == "create" })
        assertEquals(1, rig.runner.jobs.value.size)
        rig.scope.cancel()
    }

    @Test
    fun `live progress follows the engine`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.engine.emit(1, reading(25))

        assertEquals(25L, rig.controller.state.value.live.getValue(1).completedResources)
        rig.scope.cancel()
    }

    @Test
    fun `a finished download is listed as saved and the next one may start`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.engine.emit(1, reading(100, complete = true))

        val state = rig.controller.state.value
        assertEquals(0, state.jobsRunning)
        assertFalse(state.downloadRunning)
        assertTrue(state.regions.single().progress.complete)
        rig.scope.cancel()
    }

    @Test
    fun `an area that is too big starts nothing and says why`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.download(tooBig, null)

        assertTrue(rig.engine.calls.isEmpty())
        assertEquals(OfflineMapsPaneModel.button(tooBig, rig.controller.env()).note, rig.controller.state.value.notice)
        rig.scope.cancel()
    }

    @Test
    fun `without the request limit a tap starts nothing and says unavailable`() = runTest {
        val rig = rig(throttle = false)
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.download(parisOption, null)

        assertTrue(rig.engine.calls.isEmpty())
        assertEquals(OfflineMapsCopy.UNAVAILABLE, rig.controller.state.value.notice)
        assertEquals(0, rig.runner.jobs.value.size)
        rig.scope.cancel()
    }

    // ---- the mobile-data question ----

    @Test
    fun `on mobile data the tap only asks, and declining starts nothing`() = runTest {
        val rig = rig(metered = true)
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.download(parisOption, null)
        assertTrue(rig.engine.calls.isEmpty())
        val question = rig.controller.state.value.question!!
        assertEquals(parisOption.estimate!!.bytes, question.bytes)

        rig.controller.declineMetered()
        assertNull(rig.controller.state.value.question)
        assertTrue(rig.engine.calls.isEmpty())
        assertEquals(0, rig.runner.jobs.value.size)
        rig.scope.cancel()
    }

    @Test
    fun `accepting the mobile-data question starts the download`() = runTest {
        val rig = rig(metered = true)
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.controller.confirmMetered()

        assertNull(rig.controller.state.value.question)
        assertTrue(rig.engine.calls.contains("resume:1"))
        assertEquals(1, rig.runner.jobs.value.size)
        rig.scope.cancel()
    }

    // ---- pause and resume ----

    @Test
    fun `pause keeps the area and what it fetched, and never deletes`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.engine.emit(1, reading(30))

        rig.controller.pause(1)

        assertTrue(rig.engine.calls.contains("pause:1"))
        assertFalse(rig.engine.calls.any { it.startsWith("delete") })
        val state = rig.controller.state.value
        assertEquals(1, state.regions.size)
        assertFalse(state.regions.single().running)
        assertEquals(PauseReason.USER, state.pauseReasons[1])
        assertEquals(0, state.jobsRunning)
        rig.scope.cancel()
    }

    @Test
    fun `resume continues a paused area from where it stopped without creating another`() = runTest {
        val rig = rig()
        val region = rig.engine.stored()
        rig.engine.emit(region.id, reading(40))
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.resume(region.id)

        assertTrue(rig.engine.calls.contains("resume:${region.id}"))
        assertFalse(rig.engine.calls.contains("create"))
        assertTrue(rig.controller.state.value.regions.single().running)
        rig.scope.cancel()
    }

    @Test
    fun `an area that ran into the size limit is stopped, not paused, and a tap on resume fetches nothing more`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.engine.emit(1, reading(OfflineMapLimits.MAX_BYTES / 100_000 + 5))
        rig.controller.refresh()

        val state = rig.controller.state.value
        assertEquals(FailureReason.OVER_CAP, state.failures[1])
        assertFalse(state.regions.single().running)
        val env = rig.controller.env()
        val row = OfflineMapsPaneModel.savedRows(state.regions, state.pauseReasons, state.live, env, failures = state.failures).single()
        assertTrue(row.stoppedAtLimit)
        assertFalse(row.canResume)
        assertEquals(OfflineMapsCopy.failure(FailureReason.OVER_CAP), row.statusLine)

        rig.engine.calls.clear()
        rig.controller.resume(1)
        assertTrue(rig.engine.calls.none { it.startsWith("resume") || it == "create" })
        assertEquals(0, rig.runner.jobs.value.size)
        assertEquals(OfflineMapsCopy.failure(FailureReason.OVER_CAP), rig.controller.state.value.notice)
        rig.scope.cancel()
    }

    @Test
    fun `resume without the request limit starts nothing`() = runTest {
        val rig = rig(throttle = false)
        val region = rig.engine.stored()
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.resume(region.id)

        assertTrue(rig.engine.calls.isEmpty())
        assertEquals(OfflineMapsCopy.UNAVAILABLE, rig.controller.state.value.notice)
        rig.scope.cancel()
    }

    // ---- leaving and coming back ----

    @Test
    fun `closing the pane leaves the download running and a new pane picks it up`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)
        rig.engine.emit(1, reading(20))

        rig.controller.close()
        assertEquals(1, rig.runner.jobs.value.size)
        assertTrue(rig.engine.isRunning(1))
        assertFalse(rig.engine.calls.any { it.startsWith("pause") })

        val reopened = rig.scopeOf(rig.engine)
        reopened.open()
        val state = reopened.state.value
        assertTrue(state.regions.single().running)
        assertEquals(20L, state.live.getValue(1).completedResources)
        assertTrue(state.downloadRunning)

        // Pausing from the new pane stops the same job.
        reopened.pause(1)
        assertFalse(rig.engine.isRunning(1))
        assertEquals(PauseReason.USER, reopened.state.value.pauseReasons[1])
        rig.scope.cancel()
    }

    // ---- delete ----

    @Test
    fun `delete asks first and removes only the area that was named`() = runTest {
        val rig = rig()
        val keep = rig.engine.stored(record("Keep"))
        val drop = rig.engine.stored(record("Drop"))
        rig.controller.open()

        rig.controller.requestDelete(drop.id)
        assertEquals("Drop", rig.controller.state.value.deleting?.record?.name)
        assertFalse(rig.engine.calls.any { it.startsWith("delete") })

        rig.controller.cancelDelete()
        assertFalse(rig.engine.calls.any { it.startsWith("delete") })

        rig.controller.requestDelete(drop.id)
        rig.controller.confirmDelete()

        assertEquals(listOf("delete:${drop.id}"), rig.engine.calls.filter { it.startsWith("delete") })
        assertEquals(listOf(keep.id), rig.controller.state.value.regions.map { it.id })
        rig.scope.cancel()
    }

    @Test
    fun `a running area cannot be put up for deletion`() = runTest {
        val rig = rig()
        rig.controller.open()
        rig.controller.download(parisOption, null)

        rig.controller.requestDelete(1)

        assertNull(rig.controller.state.value.deleting)
        rig.scope.cancel()
    }

    // ---- limits ----

    @Test
    fun `a seventh area is refused before anything is created`() = runTest {
        val rig = rig()
        repeat(OfflineMapLimits.MAX_REGIONS) {
            val far = GeoBox.clamped(10.0 + it, 10.0, 10.1 + it, 10.1)!!
            rig.engine.stored(record("Area $it").copy(box = far, bytes = 1_000L))
        }
        rig.controller.open()
        rig.engine.calls.clear()

        rig.controller.download(parisOption, null)

        assertTrue(rig.engine.calls.isEmpty())
        assertEquals(OfflineMapsCopy.ROOM_NEEDED, rig.controller.state.value.notice)
        rig.scope.cancel()
    }
}
