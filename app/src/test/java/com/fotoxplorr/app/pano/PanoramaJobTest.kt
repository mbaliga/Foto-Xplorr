package com.fotoxplorr.app.pano

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The panorama job end to end on procedural photos: a cylinder scene photographed through a pinhole
 * camera (see [CylinderRig]) behind a fake library and a fake sink. The real aligner, layout,
 * exposure solver and compositor run; only the phone is faked. The job's contract is checked here:
 * progress, cancel, what it says when it cannot, what it refuses, and the memory ladder. How much
 * handheld imperfection costs is measured in [PanoramaJobMeasureTest].
 */
class PanoramaJobTest {

    private fun run(
        rig: CylinderRig,
        host: FakeHost,
        sink: FakeSink,
        maxPixels: Long = PanoGeometry.DEFAULT_MAX_PIXELS,
        progress: MutableList<Float>? = null,
    ): PanoramaOutcome = runBlocking {
        PanoramaJob.run(PanoramaRequest(host.assets, maxPixels), host, sink) { progress?.add(it) }
    }

    private fun failed(outcome: PanoramaOutcome): String {
        assertTrue("expected a failure, got ${(outcome as? PanoramaOutcome.Done)?.message}", outcome is PanoramaOutcome.Failed)
        return (outcome as PanoramaOutcome.Failed).message
    }

    private fun done(outcome: PanoramaOutcome): PanoramaOutcome.Done {
        assertTrue("expected a panorama, got ${(outcome as? PanoramaOutcome.Failed)?.message}", outcome is PanoramaOutcome.Done)
        return outcome as PanoramaOutcome.Done
    }

    // ---- the happy path -----------------------------------------------------------------------

    @Test
    fun `a sweep is joined, saved once, and progress rises to 1`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        val progress = Collections.synchronizedList(ArrayList<Float>())
        val result = done(run(rig, host, sink, progress = progress))

        assertEquals(1, sink.published.size)
        val saved = sink.published.single()
        assertEquals("the first photo's date", host.assets.first().dateTakenMillis, saved.dateTakenMillis)
        assertEquals("named after the first photo", host.assets.first().displayName, saved.sourceName)
        assertEquals(saved.crop.width, result.width)
        assertEquals(saved.crop.height, result.height)
        assertEquals("content://fake/published/1", result.uri)
        assertEquals(PanoCopy.SAVED, result.message)
        assertFalse(result.steppedDown)

        val shown = progress.toList()
        assertEquals("the bar ends at 1", 1f, shown.last(), 0f)
        assertTrue("and only rises: $shown", shown.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("with steps in between, not a jump: ${shown.size}", shown.size >= 10)
        assertTrue("every value is a fraction", shown.all { it in 0f..1f })

        val error = errorAgainstScene(rig, sink)
        println("PANO-JOB clean cylinder, 5 photos, 30% overlap: canvas $error")
        assertTrue("the picture is the scene: $error", error.mean <= 1.5)
        assertTrue("the whole canvas is released", sink.canvases.all { it.released })
    }

    @Test
    fun `photos are read twice, small for alignment and then at the canvas size, each through the host`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        done(run(rig, host, FakeSink()))
        assertEquals("one small read per photo", 5, host.workingCalls.size)
        assertEquals(listOf(0, 1, 2, 3, 4), host.workingCalls.map { it.index })
        val later = host.calls.filter { it.limits.maxLongEdge != PanoramaJob.WORKING_EDGE }
        assertEquals("one full read per photo", 5, later.size)
        for (call in later) {
            // The canvas is drawn at scale 1, so each photo is asked for at its own size.
            assertEquals(640, call.limits.maxLongEdge)
            assertEquals(640L * 480, call.limits.maxPixels)
        }
        assertEquals("the focal length is read once per photo", 5, host.focalReads.get())
    }

    @Test
    fun `big photos are aligned from a 1200 px copy and composed at the canvas scale`() {
        // 1600 x 1200 photos, three of them: the alignment copy is 3/4 size, and a 1.2 MP budget makes the canvas smaller still.
        val rig = CylinderRig(photoW = 1600, photoH = 1200, count = 3, step = 1000, tops = listOf(0, 9, 4), seed = 8)
        val host = rigHost(rig)
        val sink = FakeSink()
        val result = done(run(rig, host, sink, maxPixels = 1_200_000))
        assertTrue("working copies are at most 1200 px: ${host.workingCalls.map { it.limits.maxLongEdge }}", host.workingCalls.all { it.limits.maxLongEdge == 1200 })
        val canvas = sink.canvases.first()
        assertTrue("the canvas is within the budget: ${canvas.width}x${canvas.height}", canvas.width.toLong() * canvas.height <= 1_200_000)
        val later = host.calls.filter { it.limits.maxLongEdge != PanoramaJob.WORKING_EDGE }
        assertTrue("photos are decoded below their own size: ${later.map { it.limits.maxLongEdge }}", later.all { it.limits.maxLongEdge < 1600 })
        assertTrue(result.width <= canvas.width && result.height <= canvas.height)
        // Scaled: the canvas pixel (x, y) shows scene pixel (x, y) / scale.
        val scale = canvas.width.toDouble() / (rig.lefts.last() + rig.warpedW)
        val crop = sink.published.single().crop
        var sum = 0.0
        var n = 0
        val truth = DoubleArray(3)
        for (y in crop.top until crop.bottom step 3) for (x in crop.left until crop.right step 3) {
            rig.scene.expected(((x + 0.5) / scale - 0.5).toInt(), (((y + 0.5) / scale - 0.5) + rig.topsUsed.min()).toInt(), 1.0, truth)
            val p = canvas.inner.pixels[y * canvas.width + x]
            sum += kotlin.math.abs(((p shr 8) and 0xFF) - truth[1])
            n++
        }
        println("PANO-JOB 1600x1200 x3 at scale %.2f: green mean error %.2f over %d samples".format(scale, sum / n, n))
        // A scale-0.5 picture compared with full-resolution truth at a nearest pixel is soft by construction.
        assertTrue("the picture is the scene, softened by the shrink: ${sum / n}", sum / n <= 12.0)
    }

    // ---- what it refuses --------------------------------------------------------------------

    @Test
    fun `fewer than two or more than twelve photos are refused before anything is read`() {
        val rig = CylinderRig(count = 5)
        for (count in listOf(0, 1, 13)) {
            val host = FakeHost(List(count) { rig.asset(it % 5) }, { PanoSize(640, 480) }, { 28 }) { i, w, h -> rig.render(i % 5, w, h) }
            val sink = FakeSink()
            assertEquals(PanoCopy.NEED_PHOTOS, failed(run(rig, host, sink)))
            assertTrue("$count photos: nothing decoded", host.calls.isEmpty())
            assertEquals(0, host.focalReads.get())
            assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
        }
    }

    @Test
    fun `a protected photo is refused at the start and no photo is read at all`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        host.protectedIds += host.assets[3].id
        val sink = FakeSink()
        assertEquals(PanoCopy.PROTECTED, failed(run(rig, host, sink)))
        assertTrue("no pixel of any photo was read", host.calls.isEmpty())
        assertEquals("not even the EXIF", 0, host.focalReads.get())
        assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
    }

    @Test
    fun `a photo protected while the job runs stops it and nothing is saved`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // The fourth photo is protected just as the full-size pass is about to read it.
        host.onDecode = { index, count -> if (count == 5 + 4 && index == 3) host.protectedIds += host.assets[3].id }
        assertEquals(PanoCopy.PROTECTED, failed(run(rig, host, sink)))
        assertTrue(sink.published.isEmpty())
        assertTrue("the canvas is handed back", sink.canvases.all { it.released })
    }

    @Test
    fun `a photo protected after the drawing but before the file is created is caught`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // The last full-size read is the 10th decode; protect the second photo as it is handed back.
        host.onDecode = { _, count -> if (count == 10) host.protectedIds += host.assets[1].id }
        // The job's own look, right before it hands the canvas to the sink, sees the change.
        sink.ignoresLook = true
        assertEquals(PanoCopy.PROTECTED, failed(run(rig, host, sink)))
        assertTrue("nothing was published", sink.published.isEmpty())
    }

    @Test
    fun `the sink is asked again right before the file is created and can refuse`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // Everything the job could check passes; the photo is protected inside publish, before the sink's own look.
        sink.beforeLook = { host.protectedIds += host.assets[0].id }
        assertEquals(PanoCopy.PROTECTED, failed(run(rig, host, sink)))
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `photos of different sizes or lenses are refused rather than stitched wrongly`() {
        val rig = CylinderRig()
        // A portrait photo among landscapes.
        val portrait = FakeHost(rig.assets(), { if (it == 2) PanoSize(480, 640) else PanoSize(640, 480) }, { 28 }) { i, w, h -> rig.render(i, w, h) }
        assertEquals(PanoCopy.MIXED, failed(run(rig, portrait, FakeSink())))
        // One photo through a different lens.
        val tele = rigHost(rig, focal35 = { if (it == 3) 56 else 28 })
        val sink = FakeSink()
        assertEquals(PanoCopy.MIXED, failed(run(rig, tele, sink)))
        assertTrue("the full-size pass never started", tele.calls.all { it.limits.maxLongEdge == PanoramaJob.WORKING_EDGE })
        // A lens that rounds differently (27 and 28 mm) is the same camera.
        val rounding = rigHost(rig, focal35 = { if (it % 2 == 0) 27 else 28 })
        assertTrue(run(rig, rounding, FakeSink()) is PanoramaOutcome.Done)
    }

    // ---- what it says when it cannot --------------------------------------------------------

    @Test
    fun `a photo that cannot be read is named by its place in the strip`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        host.unreadable += 2
        val sink = FakeSink()
        assertEquals("Photo 3 could not be read.", failed(run(rig, host, sink)))
        assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
    }

    @Test
    fun `a photo that vanishes between the small read and the full read ends the job with its number`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        host.unreadableAfterWorking += 3
        val sink = FakeSink()
        assertEquals("Photo 4 could not be read.", failed(run(rig, host, sink)))
        assertTrue("nothing saved", sink.published.isEmpty())
        assertTrue("and the canvas given back", sink.canvases.isNotEmpty() && sink.canvases.all { it.released })
    }

    @Test
    fun `a photo that does not match the one before it is named`() {
        // Photos 1 and 2 are one scene; 3 and 4 are another: the break is between 2 and 3.
        val a = CylinderRig(count = 2, seed = 5)
        val b = CylinderRig(count = 2, seed = 6)
        val host = FakeHost(
            List(4) { a.asset(it) }, { PanoSize(640, 480) }, { 28 },
        ) { i, w, h -> if (i < 2) a.render(i, w, h) else b.render(i - 2, w, h) }
        val sink = FakeSink()
        assertEquals("Photo 3 does not match the one before it.", failed(run(a, host, sink)))
        assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
    }

    @Test
    fun `when no two photos match it says so instead of joining a guess`() {
        val a = CylinderRig(count = 1, seed = 5)
        val b = CylinderRig(count = 1, seed = 6)
        val host = FakeHost(List(2) { a.asset(it) }, { PanoSize(640, 480) }, { 28 }) { i, w, h -> if (i == 0) a.render(0, w, h) else b.render(0, w, h) }
        val sink = FakeSink()
        assertEquals(PanoCopy.NO_PAIR, failed(run(a, host, sink)))
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a flat grey sweep with nothing to match is not joined`() {
        val rig = CylinderRig(count = 3)
        val flat = PanoPixels(640, 480, IntArray(640 * 480) { 0xFF808080.toInt() })
        val host = FakeHost(rig.assets(), { PanoSize(640, 480) }, { 28 }) { _, w, h -> PanoPixels(w, h, flat.argb.copyOf(w * h)) }
        assertEquals(PanoCopy.NO_PAIR, failed(run(rig, host, FakeSink())))
    }

    @Test
    fun `an unexpected exception from the library becomes a plain sentence, never a throw`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        host.onDecode = { index, _ -> if (index == 1) throw IllegalStateException("ENOENT at /storage/emulated/0/secret") }
        val message = failed(run(rig, host, FakeSink()))
        assertEquals(PanoCopy.FAILED, message)
        assertFalse("none of the exception text leaks", message.contains("secret"))
    }

    @Test
    fun `a sink that cannot save says so`() {
        val rig = CylinderRig()
        val sink = FakeSink().also { it.failWith = java.io.IOException("disk full") }
        assertEquals(PanoCopy.SAVE_FAILED, failed(run(rig, rigHost(rig), sink)))
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.canvases.all { it.released })
    }

    // ---- the lens ---------------------------------------------------------------------------

    @Test
    fun `a photo with no lens in its EXIF gets a guessed field of view and a visible note`() {
        val rig = CylinderRig()
        val known = done(run(rig, rigHost(rig), FakeSink()))
        assertFalse(known.notes.contains(PanoCopy.LENS_GUESSED))
        val guessed = done(run(rig, rigHost(rig, focal35 = { null }), FakeSink()))
        assertTrue("the note says straight lines may look curved", guessed.notes.contains(PanoCopy.LENS_GUESSED))
        assertTrue(guessed.message.contains("straight lines may look curved"))
        // One photo of five missing its tag is enough for the note.
        val one = done(run(rig, rigHost(rig, focal35 = { if (it == 1) null else 28 }), FakeSink()))
        assertTrue(one.notes.contains(PanoCopy.LENS_GUESSED))
    }

    @Test
    fun `the lens is guessed when the tag is missing or implausible, not when it is a real lens`() {
        assertTrue(PanoramaJob.lensWasGuessed(null, 4000, 3000))
        assertTrue("0 is no lens", PanoramaJob.lensWasGuessed(0, 4000, 3000))
        assertTrue("a 5000 mm tag is a bad tag", PanoramaJob.lensWasGuessed(5000, 4000, 3000))
        assertTrue("so is 3 mm", PanoramaJob.lensWasGuessed(3, 4000, 3000))
        assertFalse(PanoramaJob.lensWasGuessed(26, 4000, 3000))
        assertFalse(PanoramaJob.lensWasGuessed(13, 4000, 3000))
        assertFalse(PanoramaJob.lensWasGuessed(120, 4000, 3000))
    }

    @Test
    fun `one camera means the same size and about the same field of view`() {
        val landscape = PanoSize(4000, 3000)
        val f = PanoGeometry.focalPixels(26, 4000, 3000)
        assertTrue(PanoramaJob.sameCamera(listOf(landscape, landscape), listOf(f, f)))
        assertTrue("a pixel of rounding", PanoramaJob.sameCamera(listOf(landscape, PanoSize(4000, 2999)), listOf(f, f)))
        assertFalse("portrait", PanoramaJob.sameCamera(listOf(landscape, PanoSize(3000, 4000)), listOf(f, f)))
        assertFalse("half the size", PanoramaJob.sameCamera(listOf(landscape, PanoSize(2000, 1500)), listOf(f, f / 2)))
        assertFalse("another lens", PanoramaJob.sameCamera(listOf(landscape, landscape), listOf(f, f * 1.5)))
        assertTrue("27 vs 26 mm", PanoramaJob.sameCamera(listOf(landscape, landscape), listOf(f, PanoGeometry.focalPixels(27, 4000, 3000))))
    }

    // ---- the size cap and the memory ladder --------------------------------------------------

    @Test
    fun `the canvas never exceeds the pixel budget of the request`() {
        val rig = CylinderRig()
        val sink = FakeSink()
        val result = done(run(rig, rigHost(rig), sink, maxPixels = 400_000))
        val canvas = sink.canvases.first()
        assertTrue("${canvas.width}x${canvas.height} within 400000", canvas.width.toLong() * canvas.height <= 400_000)
        assertTrue(result.width.toLong() * result.height <= 400_000)
        assertFalse("planned at the budget, not stepped down", result.steppedDown)
    }

    @Test
    fun `running out of memory takes the next smaller canvas on a new one and says so`() {
        val rig = CylinderRig()
        val sink = FakeSink()
        sink.oomAbove = 700_000
        val result = done(run(rig, rigHost(rig), sink))
        assertTrue(result.steppedDown)
        assertTrue("the note says it was saved smaller", result.notes.contains(PanoCopy.SAVED_SMALLER))
        assertTrue("saved smaller is in the message", result.message.contains(PanoCopy.SAVED_SMALLER))
        val saved = sink.published.single().canvas
        assertTrue("a rung under the failed one: ${saved.width}x${saved.height}", saved.width.toLong() * saved.height <= 700_000)
        assertEquals("the canvas that did not fit was never created, the one that did was handed back", 1, sink.canvases.size)
        assertTrue(sink.canvases.all { it.released })
    }

    @Test
    fun `running out of memory while drawing also takes the next rung, releasing the partly drawn canvas`() {
        val rig = CylinderRig()
        val sink = FakeSink()
        var thrown = false
        val host = rigHost(rig)
        // The first full-size photo cannot be held on the first attempt (decode count 6 is the first full-size read).
        host.onDecode = { _, count -> if (count == 7 && !thrown) { thrown = true; throw OutOfMemoryError("test: decode") } }
        val result = done(run(rig, host, sink))
        assertTrue(result.steppedDown)
        assertEquals("two canvases: the abandoned one and the one saved", 2, sink.canvases.size)
        assertTrue(sink.canvases.all { it.released })
        assertTrue(sink.canvases[1].width.toLong() * sink.canvases[1].height < sink.canvases[0].width.toLong() * sink.canvases[0].height)
    }

    @Test
    fun `when every size runs out of memory it says so and saves nothing`() {
        val rig = CylinderRig()
        val sink = FakeSink()
        sink.oomAbove = 1_000
        assertEquals(PanoCopy.OUT_OF_MEMORY, failed(run(rig, rigHost(rig), sink)))
        assertTrue(sink.published.isEmpty())
    }

    // ---- cancel -------------------------------------------------------------------------------

    /** Runs the job and cancels it from inside the library call that makes [at] true; returns whether it ended cancelled, and how long the stop took. */
    private fun cancelWhen(host: FakeHost, sink: FakeSink, at: (Int) -> Boolean): Pair<Boolean, Long> {
        val cancelledAt = java.util.concurrent.atomic.AtomicLong(0)
        return runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                PanoramaJob.run(PanoramaRequest(host.assets), host, sink)
            }
            host.onDecode = { _, count -> if (at(count) && cancelledAt.compareAndSet(0, System.nanoTime())) job.cancel() }
            job.start()
            withTimeout(60_000) { job.join() }
            job.isCancelled to (System.nanoTime() - cancelledAt.get()) / 1_000_000
        }
    }

    @Test
    fun `cancelling during the full-size pass stops the job promptly and leaves nothing behind`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // Decode 8 is the third full-size read.
        val (cancelled, millis) = cancelWhen(host, sink) { it == 8 }
        assertTrue("the job ended as cancelled", cancelled)
        println("PANO-JOB cancel during the full-size pass: stopped $millis ms after the request")
        assertTrue("promptly: $millis ms", millis < 3_000)
        assertTrue("no panorama was saved", sink.published.isEmpty())
        assertTrue("the canvas was handed back", sink.canvases.all { it.released })
        assertTrue("the later photos were never read: ${host.calls.size} reads", host.calls.size <= 10)
    }

    @Test
    fun `cancelling during the alignment stops before any canvas exists`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        val (cancelled, _) = cancelWhen(host, sink) { it == 2 }
        assertTrue(cancelled)
        assertTrue(sink.canvases.isEmpty() && sink.published.isEmpty())
        assertTrue("the rest of the photos were not read: ${host.calls.size}", host.calls.size <= 3)
    }

    @Test
    fun `cancelling after the last photo is drawn saves nothing`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // Decode 10 is the last full-size read; the drawing finishes after it.
        val (cancelled, _) = cancelWhen(host, sink) { it == 10 }
        assertTrue(cancelled)
        assertTrue("no file was asked for", sink.published.isEmpty())
        assertTrue(sink.canvases.all { it.released })
    }

    // ---- through the JobRunner ----------------------------------------------------------------

    @Test
    fun `launched on the job runner it reports progress, then a panorama, once`() {
        val rig = CylinderRig()
        val host = rigHost(rig)
        val sink = FakeSink()
        // Unconfined starts the job in place, like the view model's Main.immediate scope: the job list
        // is updated after launch returns, so a job that finished first on another thread would never leave it.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val runner = JobRunner(scope)
        val seen = Collections.synchronizedList(ArrayList<Float?>())
        val result = runBlocking {
            val wait = scope.async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runner.jobs.collect { list -> list.firstOrNull()?.let { seen += it.progress } }
            }
            PanoramaJob.launch(runner, PanoramaRequest(host.assets), host, sink)
            val outcome = withTimeout(60_000) { wait.await() }
            observer.cancel()
            outcome
        }
        assertEquals(PanoCopy.JOB_TITLE, result.title)
        assertEquals(ActivityKind.EXPORTING, result.kind)
        val done = result.result.getOrThrow() as PanoramaOutcome.Done
        assertEquals(PanoCopy.SAVED, done.message)
        assertEquals(1, sink.published.size)
        assertTrue("the shade saw the bar move: $seen", seen.filterNotNull().isNotEmpty())
        assertTrue("no job is left running", runner.jobs.value.isEmpty())
    }

    @Test
    fun `launched on the job runner a failure arrives as the sentence, and cancel arrives as a cancel`() {
        val rig = CylinderRig()
        // Unconfined starts the job in place, like the view model's Main.immediate scope: the job list
        // is updated after launch returns, so a job that finished first on another thread would never leave it.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val runner = JobRunner(scope)
        val host = rigHost(rig)
        host.unreadable += 0
        val failure = runBlocking {
            val wait = scope.async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            PanoramaJob.launch(runner, PanoramaRequest(host.assets), host, FakeSink())
            withTimeout(60_000) { wait.await() }.result.exceptionOrNull()
        }
        assertTrue(failure is PanoramaFailure)
        assertEquals("Photo 1 could not be read.", failure!!.message)

        val slow = rigHost(rig)
        val sink = FakeSink()
        val handle = java.util.concurrent.atomic.AtomicReference<JobHandle?>(null)
        slow.onDecode = { _, count -> if (count == 7) handle.get()?.cancel() }
        val cancelled = runBlocking {
            val wait = scope.async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            handle.set(PanoramaJob.launch(runner, PanoramaRequest(slow.assets), slow, sink))
            withTimeout(60_000) { wait.await() }.result.exceptionOrNull()
        }
        assertTrue("a cancelled job reports a cancellation: $cancelled", cancelled is CancellationException)
        assertTrue(sink.published.isEmpty())
        assertNull(runner.jobs.value.firstOrNull())
    }
}
