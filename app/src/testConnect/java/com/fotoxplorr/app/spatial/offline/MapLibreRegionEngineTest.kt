package com.fotoxplorr.app.spatial.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connect engine's rules, proved against a fake backend and a fake request cap. Nothing here
 * proves what the real map library does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapLibreRegionEngineTest {

    private val box = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val estimate = (RegionBudget.plan(box) as BudgetVerdict.Fits).estimate
    private val record = RegionRecord(
        name = "Paris", box = box, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1L, lastUsedMillis = 1L, bytes = estimate.bytes, tileCount = estimate.tileCount,
    )

    private val backend = FakeOfflineBackend()
    private val cap = FakeRequestCap()

    private fun engine(
        b: FakeOfflineBackend = backend,
        c: FakeRequestCap = cap,
        verified: Boolean = true,
    ) = MapLibreRegionEngine(b, c, dispatcher = Dispatchers.Unconfined, releaseGateOpen = verified)

    private fun status(done: Long, bytes: Long = done * 100_000, complete: Boolean = false) = BackendStatus(
        completedResourceCount = done, completedResourceSize = bytes, completedTileCount = done / 2,
        requiredResourceCount = 100, requiredCountIsPrecise = true, complete = complete,
    )

    private suspend fun MapLibreRegionEngine.createOne(rec: RegionRecord = record): Long =
        (create(rec) as EngineResult.Ok).value.id

    // ---- creating and listing never fetch ----

    @Test
    fun `create stores a paused region in the Liberty style and fetches nothing`() = runTest {
        val engine = engine()
        val id = engine.createOne()

        val stored = backend.regions.getValue(id)
        assertFalse(stored.active)
        assertEquals("https://tiles.openfreemap.org/styles/liberty", stored.definition!!.styleUrl)
        assertEquals(box, stored.definition!!.box)
        assertEquals(0, stored.definition!!.minZoom)
        assertEquals(14, stored.definition!!.maxZoom)
        assertEquals(record, RegionRecord.decode(stored.metadata!!.toString(Charsets.UTF_8)))
        assertFalse(backend.calls.any { it.startsWith("setActive") || it.startsWith("listen") })
        assertFalse(cap.inForce)
        // The probe at construction is the only time the cap was touched.
        assertEquals(1, cap.installCalls)
        assertEquals(1, cap.restoreCalls)
    }

    @Test
    fun `until the cap is verified on a device a release build offers no download and never probes the cap`() = runTest {
        val engine = engine(verified = false)
        val id = engine.createOne()

        assertFalse(engine.throttleInstalled)
        assertEquals(0, cap.installCalls)
        assertEquals(0, cap.restoreCalls)
        assertEquals(
            EngineFailure.THROTTLE_NOT_INSTALLED,
            (engine.resume(id) as EngineResult.Failure).reason,
        )
        assertFalse(backend.calls.any { it.startsWith("setActive") })
        assertEquals(0, cap.installCalls)
    }

    @Test
    fun `the release gate is shut in the source until someone confirms the cap with a logging proxy`() {
        assertFalse(MapLibreRegionEngine.CAP_VERIFIED_ON_DEVICE)
        assertFalse(MapLibreRegionEngine.downloadsAllowed(verified = false, debugBuild = false))
        assertTrue(MapLibreRegionEngine.downloadsAllowed(verified = false, debugBuild = true))
        assertTrue(MapLibreRegionEngine.downloadsAllowed(verified = true, debugBuild = false))
    }

    @Test
    fun `a box over the date line is refused until that is verified`() = runTest {
        val crossing = GeoBox.clamped(179.9, 10.0, -179.9, 10.1)!!
        assertTrue(crossing.crossesAntimeridian)
        val result = engine().create(record.copy(box = crossing))
        assertEquals(EngineFailure.ENGINE_ERROR, (result as EngineResult.Failure).reason)
        assertTrue(backend.regions.isEmpty())
    }

    @Test
    fun `list never starts a download and reports nothing as running`() = runTest {
        val engine = engine()
        engine.createOne()
        val listed = (engine.list() as EngineResult.Ok).value

        assertEquals(1, listed.size)
        assertFalse(listed.single().running)
        assertFalse(backend.calls.any { it.startsWith("setActive") })
        assertFalse(cap.inForce)
    }

    @Test
    fun `list skips what this app did not write and leaves it alone`() = runTest {
        backend.addForeign(null)
        backend.addForeign("not a record".toByteArray())
        val ours = backend.addPersisted(record, status(10))

        val listed = (engine().list() as EngineResult.Ok).value

        assertEquals(listOf(ours), listed.map { it.id })
        assertEquals(3, backend.regions.size)
        assertFalse(backend.calls.any { it.startsWith("delete") })
    }

    @Test
    fun `after a relaunch a half-done region comes back paused with its progress`() = runTest {
        val id = backend.addPersisted(record, status(40))
        val relaunched = engine()

        val listed = (relaunched.list() as EngineResult.Ok).value.single()

        assertEquals(id, listed.id)
        assertFalse(listed.running)
        assertEquals(40L, listed.progress.completedResources)
        assertFalse(listed.progress.complete)
        assertEquals(listed.progress, relaunched.progress(id).first())
        assertFalse(backend.isActive(id))
        assertFalse(cap.inForce)
    }

    @Test
    fun `a failing library is an engine error, not a crash`() = runTest {
        backend.failList = true
        val result = engine().list()
        assertEquals(EngineFailure.ENGINE_ERROR, (result as EngineResult.Failure).reason)
    }

    // ---- the request cap ----

    @Test
    fun `throttleInstalled is true when the cap can be applied, and the probe leaves it off`() {
        val engine = engine()
        assertTrue(engine.throttleInstalled)
        assertFalse(cap.inForce)
    }

    @Test
    fun `without a usable cap nothing can start and nothing is fetched`() = runTest {
        val noCap = FakeRequestCap(installWorks = false)
        val engine = engine(c = noCap)
        val id = engine.createOne()

        assertFalse(engine.throttleInstalled)
        val result = engine.resume(id)

        assertEquals(EngineFailure.THROTTLE_NOT_INSTALLED, (result as EngineResult.Failure).reason)
        assertFalse(backend.calls.any { it.startsWith("setActive") })
        assertFalse(backend.isActive(id))
    }

    @Test
    fun `a cap that stops working after the probe refuses the download and turns the button off`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        cap.installWorks = false

        val result = engine.resume(id)

        assertEquals(EngineFailure.THROTTLE_NOT_INSTALLED, (result as EngineResult.Failure).reason)
        assertFalse(engine.throttleInstalled)
        assertFalse(backend.isActive(id))
        assertFalse(backend.calls.any { it.startsWith("setActive") })
    }

    @Test
    fun `resume puts the cap in force and starts the region, pause takes the cap off`() = runTest {
        val engine = engine()
        val id = engine.createOne()

        assertTrue(engine.resume(id) is EngineResult.Ok)
        assertTrue(cap.inForce)
        assertTrue(backend.isActive(id))
        assertTrue((engine.list() as EngineResult.Ok).value.single().running)

        assertTrue(engine.pause(id) is EngineResult.Ok)
        assertFalse(cap.inForce)
        assertFalse(backend.isActive(id))
        assertFalse((engine.list() as EngineResult.Ok).value.single().running)
    }

    @Test
    fun `the cap is applied once for two downloads and removed when the last one stops`() = runTest {
        val engine = engine()
        val a = engine.createOne()
        val b = engine.createOne(record.copy(name = "Lyon"))
        val installsBefore = cap.installCalls

        engine.resume(a)
        engine.resume(b)
        assertEquals(installsBefore + 1, cap.installCalls)

        engine.pause(a)
        assertTrue("one region is still downloading", cap.inForce)
        engine.pause(b)
        assertFalse(cap.inForce)
    }

    @Test
    fun `the cap is removed when a region completes by itself`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)

        backend.emit(id, status(100, complete = true))

        assertFalse(cap.inForce)
        assertFalse(backend.isActive(id))
        assertNull(backend.regions.getValue(id).listener)
        assertTrue(engine.progress(id).first().complete)
    }

    @Test
    fun `a library that refuses to start leaves the cap off and the region paused`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        backend.failSetActive = true

        val result = engine.resume(id)

        assertEquals(EngineFailure.ENGINE_ERROR, (result as EngineResult.Failure).reason)
        assertFalse(cap.inForce)
        assertFalse(backend.isActive(id))
        assertNull(backend.regions.getValue(id).listener)
    }

    @Test
    fun `resume of a region nobody listed is refused before the cap is touched`() = runTest {
        val engine = engine()
        val installsBefore = cap.installCalls
        val result = engine.resume(99L)

        assertEquals(EngineFailure.NO_SUCH_REGION, (result as EngineResult.Failure).reason)
        assertEquals(installsBefore, cap.installCalls)
    }

    // ---- pause keeps, delete removes ----

    @Test
    fun `pause keeps the region and what it fetched and publishes the figures it stopped at`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        backend.emit(id, status(30))
        backend.emitError(id, DownloadErrorKind.CONNECTION)

        engine.pause(id)

        assertFalse(backend.calls.any { it.startsWith("delete") })
        assertTrue(backend.regions.containsKey(id))
        val last = engine.progress(id).first()
        assertEquals(30L, last.completedResources)
        assertNull("the error that came with the last reading is not carried over", last.error)
    }

    @Test
    fun `a reading that arrives after the pause is ignored`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        val listener = backend.regions.getValue(id).listener!!
        backend.emit(id, status(30))
        engine.pause(id)

        listener.onStatus(status(99))

        assertEquals(30L, engine.progress(id).first().completedResources)
    }

    @Test
    fun `a failing status read during pause still stops the region and removes the cap`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        backend.failStatus = true

        assertTrue(engine.pause(id) is EngineResult.Ok)

        assertFalse(backend.isActive(id))
        assertFalse(cap.inForce)
    }

    @Test
    fun `delete removes the region, ends its feed and takes the cap off`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        val seen = mutableListOf<RegionProgress>()
        var ended = false
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.progress(id).collect { seen += it }
            ended = true
        }
        backend.emit(id, status(10))

        assertTrue(engine.delete(id) is EngineResult.Ok)

        assertTrue(ended)
        collector.cancel()
        assertTrue(backend.regions.isEmpty())
        assertFalse(cap.inForce)
        assertEquals(10L, seen.last().completedResources)
        assertEquals(emptyList<RegionProgress>(), engine.progress(id).toList())
        assertEquals(EngineFailure.NO_SUCH_REGION, (engine.pause(id) as EngineResult.Failure).reason)
    }

    @Test
    fun `a delete the library refuses reports the failure and keeps the region`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        backend.failDelete = true

        assertEquals(EngineFailure.ENGINE_ERROR, (engine.delete(id) as EngineResult.Failure).reason)
        engine.progress(id).first() // the feed is still readable
    }

    // ---- the progress feed ----

    @Test
    fun `progress gives the current figures first, then each change, and carries an error once`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        val seen = mutableListOf<RegionProgress>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { engine.progress(id).collect { seen += it } }

        backend.emit(id, status(10))
        backend.emitError(id, DownloadErrorKind.SERVER)
        backend.emit(id, status(20))
        collector.cancel()

        assertEquals(listOf(0L, 10L, 10L, 20L), seen.map { it.completedResources })
        assertEquals(listOf(null, null, DownloadErrorKind.SERVER, null), seen.map { it.error })
        assertEquals(20L * 100_000, seen.last().completedBytes)
        assertEquals(10L, seen.last().completedTiles)
    }

    @Test
    fun `collecting progress does not start or stop anything`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.progress(id).first()
        assertFalse(backend.calls.any { it.startsWith("setActive") })
        assertFalse(backend.isActive(id))
    }

    @Test
    fun `progress of an unknown region is empty`() = runTest {
        assertEquals(emptyList<RegionProgress>(), engine().progress(404L).toList())
    }

    @Test
    fun `an error reading arriving while not downloading is dropped`() = runTest {
        val engine = engine()
        val id = engine.createOne()
        engine.resume(id)
        val listener = backend.regions.getValue(id).listener!!
        engine.pause(id)

        listener.onError(DownloadErrorKind.CONNECTION)

        assertNull(engine.progress(id).first().error)
    }
}
