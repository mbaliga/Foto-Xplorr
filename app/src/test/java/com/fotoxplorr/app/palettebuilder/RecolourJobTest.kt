package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.BLUE
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.FakeHost
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.GREEN
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RED
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RecordingSink
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.YELLOW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.Collections

/**
 * The recolour job over fakes: what it asks, what it refuses, how it ends, and that a cancel leaves no file behind.
 * The real writer (MediaStore, EXIF) is the editor's and is not run here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecolourJobTest {

    private val warm = listOf(0xFFB03A2E.toInt(), 0xFFF39C12.toInt(), 0xFFFDEBD0.toInt())
    private val flat = RecolourSettings(strength = 100, keepShading = false)

    private fun photo(): Bitmap = PaletteTestKit.bitmap(10, 6) { x, y -> listOf(RED, GREEN, BLUE, YELLOW)[(x + y) % 4] }

    private fun request(asset: MediaAsset = PaletteTestKit.asset(5), colours: List<Int> = warm, settings: RecolourSettings = flat) =
        RecolourRequest(asset, colours, settings)

    private fun run(host: PaletteHost, sink: RecolourSink, request: RecolourRequest = request(), progress: MutableList<Float>? = null): RecolourOutcome =
        runBlocking { RecolourJob.run(request, host, sink) { progress?.add(it) } }

    private fun failed(outcome: RecolourOutcome): String {
        assertTrue("expected a failure, got ${(outcome as? RecolourOutcome.Done)?.message}", outcome is RecolourOutcome.Failed)
        return (outcome as RecolourOutcome.Failed).message
    }

    // ---- the happy path ----

    @Test
    fun `a photo is recoloured by the palette's own maths and saved once as a new file`() {
        val src = photo()
        val want = PaletteRecolour(warm, amount = 1f, keepLightness = 0f).mapAll(PaletteTestKit.pixels(src))
        val sink = RecordingSink()
        val asset = PaletteTestKit.asset(5)
        val outcome = run(FakeHost({ src }), sink, request(asset))

        assertTrue(outcome is RecolourOutcome.Done)
        outcome as RecolourOutcome.Done
        assertEquals("content://media/external/images/media/900", outcome.ref)
        assertFalse(outcome.smaller)
        assertEquals(PaletteBuilderCopy.COPIED, outcome.message)
        assertEquals(1, sink.saved.size)
        assertEquals(asset, sink.saved[0].source)
        assertEquals(10, sink.saved[0].width)
        assertEquals(6, sink.saved[0].height)
        assertArrayEquals(want, sink.saved[0].pixels)
        assertTrue(sink.discarded.isEmpty())
    }

    @Test
    fun `the strength and the shading setting reach the recolour`() {
        val src = photo()
        val settings = RecolourSettings(strength = 50, keepShading = true)
        val want = PaletteRecolour(warm, amount = 0.5f, keepLightness = RecolourSettings.KEEP_SHADING).mapAll(PaletteTestKit.pixels(src))
        val sink = RecordingSink()
        run(FakeHost({ photo() }), sink, request(settings = settings))
        assertArrayEquals(want, sink.saved[0].pixels)
        assertTrue("and it is not the flat result", !want.contentEquals(PaletteRecolour(warm).mapAll(PaletteTestKit.pixels(src))))
    }

    @Test
    fun `a picture of several bands comes out as if it were done in one piece`() {
        // 1100 x 1000 is more than one million pixels, so it takes two bands.
        val w = 1100
        val h = 1000
        assertTrue(PaletteRecolour.bands(w, h).size >= 2)
        val src = PaletteTestKit.bitmap(w, h) { x, y -> ColorMath.rgb((x * 7 + y) and 255, (x + y * 3) and 255, (x * y) and 255) }
        val recolour = PaletteRecolour(warm)
        val want = recolour.mapAll(PaletteTestKit.pixels(src))
        val out = runBlocking { RecolourJob.recolourBitmap(src, recolour) }
        assertArrayEquals(want, PaletteTestKit.pixels(out))
    }

    @Test
    fun `it asks for the recolour's own size cap first`() {
        val host = FakeHost({ photo() })
        run(host, RecordingSink())
        assertEquals(1, host.limits.size)
        assertEquals(RecolourJob.MAX_EDGE, host.limits[0].maxLongEdge)
        assertEquals(RecolourJob.MAX_PIXELS, host.limits[0].maxPixels)
    }

    @Test
    fun `a photo already cut down to the cap is reported as smaller`() {
        val outcome = run(FakeHost({ photo() }, downscaled = true), RecordingSink())
        assertTrue((outcome as RecolourOutcome.Done).smaller)
        assertEquals(PaletteBuilderCopy.COPIED_SMALLER, outcome.message)
    }

    @Test
    fun `progress rises and ends at one`() {
        val progress = Collections.synchronizedList(ArrayList<Float>())
        run(FakeHost({ photo() }), RecordingSink(), progress = progress)
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sorted(), progress.toList())
        assertEquals(1f, progress.last(), 0f)
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test
    fun `the decoded bitmap is released once it has been recoloured`() {
        val src = photo()
        run(FakeHost({ src }), RecordingSink())
        assertTrue(src.isRecycled)
    }

    // ---- what it refuses ----

    @Test
    fun `a photo the host refuses is never read or saved`() {
        val host = FakeHost({ photo() }, answers = listOf(false))
        val sink = RecordingSink()
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, failed(run(host, sink)))
        assertTrue(host.limits.isEmpty())
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a photo that became protected during the job is not saved`() {
        val host = FakeHost({ photo() }, answers = listOf(true, false))
        val sink = RecordingSink()
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, failed(run(host, sink)))
        assertEquals(1, host.limits.size)
        assertTrue("asked twice, before the read and before the file", host.asked >= 2)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a video is refused`() {
        val sink = RecordingSink()
        val video = PaletteTestKit.asset(6, "v.mp4", mime = "video/mp4")
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, failed(run(FakeHost({ photo() }), sink, request(video))))
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a palette with fewer than two colours is refused rather than guessed at`() {
        val sink = RecordingSink()
        assertEquals(PaletteBuilderCopy.COPY_FAILED, failed(run(FakeHost({ photo() }), sink, request(colours = listOf(RED)))))
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a photo that cannot be read says so and saves nothing`() {
        val sink = RecordingSink()
        val host = FakeHost({ null })
        assertEquals(PaletteBuilderCopy.COPY_UNREADABLE, failed(run(host, sink)))
        assertEquals("an unreadable file is not retried at every size", 1, host.limits.size)
        assertTrue(sink.saved.isEmpty())
    }

    // ---- memory ----

    @Test
    fun `running out of memory steps down the editor's ladder and says the copy is smaller`() {
        val host = FakeHost({ photo() }, outOfMemoryFirst = 1)
        val outcome = run(host, RecordingSink())
        assertTrue(outcome is RecolourOutcome.Done)
        outcome as RecolourOutcome.Done
        assertTrue(outcome.smaller)
        assertEquals(listOf(4096, 2048), host.limits.map { it.maxLongEdge })
    }

    @Test
    fun `running out of memory at every size fails with a memory sentence and no file`() {
        val sink = RecordingSink()
        val host = FakeHost({ photo() }, outOfMemoryFirst = 100)
        assertEquals(PaletteBuilderCopy.COPY_OUT_OF_MEMORY, failed(run(host, sink)))
        assertEquals(listOf(4096, 2048, 1536), host.limits.map { it.maxLongEdge })
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `the writer stepping down on its own is reported too`() {
        val outcome = run(FakeHost({ photo() }), RecordingSink(stepDown = true))
        assertTrue((outcome as RecolourOutcome.Done).smaller)
    }

    // ---- failures of the writer ----

    @Test
    fun `a writer failure is a plain sentence, not the exception's text`() {
        val outcome = run(FakeHost({ photo() }), RecordingSink(result = Result.failure(IllegalStateException("content://x: EACCES"))))
        assertEquals(PaletteBuilderCopy.COPY_FAILED, failed(outcome))
    }

    @Test
    fun `a full phone says so`() {
        val outcome = run(FakeHost({ photo() }), RecordingSink(result = Result.failure(RecolourNoSpaceException())))
        assertEquals(PaletteBuilderCopy.COPY_NO_SPACE, failed(outcome))
    }

    @Test
    fun `an exception from the host is a failure sentence and never escapes`() {
        val host = object : PaletteHost {
            override fun mayExport(asset: MediaAsset) = true
            override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? = throw IllegalStateException("boom")
        }
        assertEquals(PaletteBuilderCopy.COPY_FAILED, failed(run(host, RecordingSink())))
    }

    @Test
    fun `out of space is recognised from the system's own text and from the cause chain`() {
        assertTrue(isOutOfSpace(RecolourNoSpaceException()))
        assertTrue(isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isOutOfSpace(RuntimeException("wrapper", IOException("No space left on device"))))
        assertFalse(isOutOfSpace(IOException("permission denied")))
        assertFalse("not an I/O error", isOutOfSpace(IllegalStateException("disk full ENOSPC")))
        assertFalse(isOutOfSpace(null))
    }

    // ---- cancel ----

    @Test
    fun `a cancel while the writer works discards the file it made and reports a cancel`() = runBlocking {
        lateinit var self: Job
        val sink = RecordingSink(onSave = { self.cancel() })
        self = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            RecolourJob.run(request(), FakeHost({ photo() }), sink)
        }
        self.start()
        self.join()
        assertTrue(self.isCancelled)
        assertEquals(listOf("content://media/external/images/media/900"), sink.discarded.toList())
    }

    @Test
    fun `a cancel before the writer starts leaves nothing to discard and never calls it`() = runBlocking {
        lateinit var self: Job
        val sink = RecordingSink()
        self = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            RecolourJob.run(request(), FakeHost({ photo() }, onDecode = { self.cancel() }), sink)
        }
        self.start()
        self.join()
        assertTrue(self.isCancelled)
        assertTrue(sink.saved.isEmpty())
        assertTrue(sink.discarded.isEmpty())
    }

    // ---- as a JobRunner job ----

    // The runner lives in the test's own single-threaded scope, like the view model's main-thread one: a job is
    // on the list before it can finish, which a multi-threaded scope does not promise for a job this quick.

    @Test
    fun `launched on the runner it is an export job and its outcome carries the copy`() = runBlocking {
        val runner = JobRunner(this)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { runner.outcomes.first() } }
        RecolourJob.launch(runner, request(), FakeHost({ photo() }), RecordingSink())
        val got = outcome.await()
        assertEquals(PaletteBuilderCopy.JOB_TITLE, got.title)
        assertEquals(ActivityKind.EXPORTING, got.kind)
        val value = got.result.getOrThrow() as RecolourOutcome.Done
        assertEquals("content://media/external/images/media/900", value.ref)
        assertFalse(runner.hasRunningJobs())
    }

    @Test
    fun `a refusal arrives as a failure with the sentence`() = runBlocking {
        val runner = JobRunner(this)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { runner.outcomes.first() } }
        RecolourJob.launch(runner, request(), FakeHost({ photo() }, answers = listOf(false)), RecordingSink())
        val error = outcome.await().result.exceptionOrNull()
        assertTrue(error is RecolourFailure)
        assertEquals(PaletteBuilderCopy.COPY_PROTECTED, error!!.message)
    }

    @Test
    fun `cancelling the running job from the runner ends it as cancelled with no file left`() = runBlocking {
        val runner = JobRunner(this)
        val sink = RecordingSink(onSave = { runner.cancelAll() })
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { runner.outcomes.first() } }
        RecolourJob.launch(runner, request(), FakeHost({ photo() }), sink)
        val error = outcome.await().result.exceptionOrNull()
        assertTrue("got $error", error is CancellationException)
        assertEquals(listOf("content://media/external/images/media/900"), sink.discarded.toList())
    }
}
