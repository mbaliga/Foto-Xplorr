package com.fotoxplorr.app.vision

import android.graphics.Bitmap
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
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
import java.util.Collections

/**
 * The corrected-copy job over fakes: what it asks, what it refuses, how it ends, and that a cancel
 * leaves no file behind. The real writer (MediaStore, EXIF) is the editor's and is not run here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CorrectedCopyJobTest {

    private val key = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
    private val palette = intArrayOf(VisionTestKit.argb(220, 40, 40), VisionTestKit.argb(40, 160, 40), VisionTestKit.argb(30, 60, 220))

    private fun photo() = ColourVisionKit.bitmap(10, 6) { x, y -> palette[(x + y) % palette.size] }

    private class FakeHost(
        private val makeBitmap: () -> Bitmap?,
        /** The answers to [mayCopy], one per ask; the last repeats. */
        private val answers: List<Boolean> = listOf(true),
        private val outOfMemoryFirst: Int = 0,
        private val onDecode: suspend () -> Unit = {},
    ) : CorrectedCopyHost {
        val limits: MutableList<DecodeLimits> = Collections.synchronizedList(ArrayList())
        var asked = 0

        override fun mayCopy(asset: MediaAsset): Boolean = answers[minOf(asked++, answers.lastIndex)]

        override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? {
            this.limits += limits
            onDecode()
            if (this.limits.size <= outOfMemoryFirst) throw OutOfMemoryError("test")
            val bitmap = makeBitmap() ?: return null
            return DecodedBitmap(bitmap, bitmap.width, bitmap.height, downscaled = false)
        }
    }

    private class FakeSink(
        private val result: Result<String> = Result.success("content://media/new/1"),
        private val onSave: suspend () -> Unit = {},
        private val stepDown: Boolean = false,
    ) : CorrectedCopySink {
        class Saved(val source: MediaAsset, val pixels: IntArray, val width: Int, val height: Int)

        val saved: MutableList<Saved> = Collections.synchronizedList(ArrayList())
        val discarded: MutableList<String> = Collections.synchronizedList(ArrayList())

        override suspend fun save(source: MediaAsset, bitmap: Bitmap, onSteppedDown: () -> Unit): Result<String> {
            // Copy now: the job recycles the bitmap when it is done.
            saved += Saved(source, ColourVisionKit.pixels(bitmap), bitmap.width, bitmap.height)
            if (stepDown) onSteppedDown()
            onSave()
            return result
        }

        override suspend fun discard(ref: String) {
            discarded += ref
        }
    }

    private fun run(
        host: CorrectedCopyHost,
        sink: CorrectedCopySink,
        asset: MediaAsset = ColourVisionKit.asset(),
        progress: MutableList<Float>? = null,
    ): CorrectedCopyOutcome = runBlocking {
        CorrectedCopyJob.run(CorrectedCopyRequest(asset, key), host, sink) { progress?.add(it) }
    }

    private fun failed(outcome: CorrectedCopyOutcome): String {
        assertTrue("expected a failure, got ${(outcome as? CorrectedCopyOutcome.Done)?.message}", outcome is CorrectedCopyOutcome.Failed)
        return (outcome as CorrectedCopyOutcome.Failed).message
    }

    // ---- the happy path ----

    @Test
    fun `a photo is filtered by the exact path and saved once as a new file`() {
        val src = photo()
        val want = Simulation.simulateAll(ColourVisionKit.pixels(src), Deficiency.DEUTAN, 1.0)
        val sink = FakeSink()
        val asset = ColourVisionKit.asset()
        val outcome = run(FakeHost({ src }), sink, asset)

        assertTrue(outcome is CorrectedCopyOutcome.Done)
        outcome as CorrectedCopyOutcome.Done
        assertEquals("content://media/new/1", outcome.ref)
        assertFalse(outcome.smaller)
        assertEquals(VisionCopy.COPY_SAVED, outcome.message)
        assertEquals(1, sink.saved.size)
        assertEquals(asset, sink.saved[0].source)
        assertEquals(10, sink.saved[0].width)
        assertEquals(6, sink.saved[0].height)
        assertArrayEquals(want, sink.saved[0].pixels)
        assertTrue(sink.discarded.isEmpty())
    }

    @Test
    fun `it asks for the editor's own export ceiling first`() {
        val host = FakeHost({ photo() })
        run(host, FakeSink())
        assertEquals(1, host.limits.size)
        assertEquals(8192, host.limits[0].maxLongEdge)
        assertEquals(64_000_000L, host.limits[0].maxPixels)
    }

    @Test
    fun `progress rises and ends at one`() {
        val progress = Collections.synchronizedList(ArrayList<Float>())
        run(FakeHost({ photo() }), FakeSink(), progress = progress)
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sorted(), progress.toList())
        assertEquals(1f, progress.last(), 0f)
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test
    fun `the decoded bitmap is released once it has been filtered`() {
        val src = photo()
        run(FakeHost({ src }), FakeSink())
        assertTrue(src.isRecycled)
    }

    // ---- what it refuses ----

    @Test
    fun `a photo the host refuses is never read or saved`() {
        val host = FakeHost({ photo() }, answers = listOf(false))
        val sink = FakeSink()
        assertEquals(VisionCopy.COPY_PROTECTED, failed(run(host, sink)))
        assertTrue(host.limits.isEmpty())
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a photo that became protected during the job is not saved`() {
        val host = FakeHost({ photo() }, answers = listOf(true, false))
        val sink = FakeSink()
        assertEquals(VisionCopy.COPY_PROTECTED, failed(run(host, sink)))
        assertEquals(1, host.limits.size)
        assertTrue("asked twice, before the read and before the file", host.asked >= 2)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a video is refused`() {
        val sink = FakeSink()
        assertEquals(VisionCopy.COPY_PROTECTED, failed(run(FakeHost({ photo() }), sink, ColourVisionKit.asset(mime = "video/mp4", name = "v.mp4"))))
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a photo that cannot be read says so and saves nothing`() {
        val sink = FakeSink()
        val host = FakeHost({ null })
        assertEquals(VisionCopy.COPY_CANNOT_READ, failed(run(host, sink)))
        assertEquals("an unreadable file is not retried at every size", 1, host.limits.size)
        assertTrue(sink.saved.isEmpty())
    }

    // ---- memory ----

    @Test
    fun `running out of memory steps down the editor's ladder and says the copy is smaller`() {
        val host = FakeHost({ photo() }, outOfMemoryFirst = 1)
        val outcome = run(host, FakeSink())
        assertTrue(outcome is CorrectedCopyOutcome.Done)
        outcome as CorrectedCopyOutcome.Done
        assertTrue(outcome.smaller)
        assertEquals(VisionCopy.COPY_SAVED_SMALLER, outcome.message)
        assertEquals(listOf(8192, 4096), host.limits.map { it.maxLongEdge })
    }

    @Test
    fun `running out of memory at every size fails with a memory sentence and no file`() {
        val sink = FakeSink()
        val host = FakeHost({ photo() }, outOfMemoryFirst = 100)
        assertEquals(VisionCopy.COPY_OUT_OF_MEMORY, failed(run(host, sink)))
        assertEquals(listOf(8192, 4096, 2048, 1536), host.limits.map { it.maxLongEdge })
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `the writer stepping down on its own is reported too`() {
        val outcome = run(FakeHost({ photo() }), FakeSink(stepDown = true))
        assertTrue((outcome as CorrectedCopyOutcome.Done).smaller)
    }

    // ---- failures of the writer ----

    @Test
    fun `a writer failure is a plain sentence, not the exception's text`() {
        val outcome = run(FakeHost({ photo() }), FakeSink(result = Result.failure(IllegalStateException("content://x: EACCES"))))
        assertEquals(VisionCopy.COPY_FAILED, failed(outcome))
    }

    @Test
    fun `a full phone says so`() {
        val outcome = run(FakeHost({ photo() }), FakeSink(result = Result.failure(CorrectedCopyNoSpaceException())))
        assertEquals(VisionCopy.COPY_NO_SPACE, failed(outcome))
    }

    @Test
    fun `an exception from the host is a failure sentence and never escapes`() {
        val host = object : CorrectedCopyHost {
            override fun mayCopy(asset: MediaAsset) = true
            override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? = throw IllegalStateException("boom")
        }
        assertEquals(VisionCopy.COPY_FAILED, failed(run(host, FakeSink())))
    }

    @Test
    fun `out of space is recognised from the system's own text and from the cause chain`() {
        assertTrue(isOutOfSpace(CorrectedCopyNoSpaceException()))
        assertTrue(isOutOfSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isOutOfSpace(RuntimeException("wrapper", java.io.IOException("No space left on device"))))
        assertFalse(isOutOfSpace(java.io.IOException("permission denied")))
        assertFalse("not an I/O error", isOutOfSpace(IllegalStateException("disk full ENOSPC")))
        assertFalse(isOutOfSpace(null))
    }

    // ---- cancel ----

    @Test
    fun `a cancel while the writer works discards the file it made and reports a cancel`() = runBlocking {
        lateinit var self: Job
        val sink = FakeSink(onSave = { self.cancel() })
        self = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            CorrectedCopyJob.run(CorrectedCopyRequest(ColourVisionKit.asset(), key), FakeHost({ photo() }), sink)
        }
        self.start()
        self.join()
        assertTrue(self.isCancelled)
        assertEquals(listOf("content://media/new/1"), sink.discarded.toList())
    }

    @Test
    fun `a cancel before the writer starts leaves nothing to discard and never calls it`() = runBlocking {
        lateinit var self: Job
        val sink = FakeSink()
        self = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            CorrectedCopyJob.run(CorrectedCopyRequest(ColourVisionKit.asset(), key), FakeHost({ photo() }, onDecode = { self.cancel() }), sink)
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
        CorrectedCopyJob.launch(runner, CorrectedCopyRequest(ColourVisionKit.asset(), key), FakeHost({ photo() }), FakeSink())
        val got = outcome.await()
        assertEquals(VisionCopy.JOB_TITLE, got.title)
        assertEquals(ActivityKind.EXPORTING, got.kind)
        val value = got.result.getOrThrow() as CorrectedCopyOutcome.Done
        assertEquals("content://media/new/1", value.ref)
        assertFalse(runner.hasRunningJobs())
    }

    @Test
    fun `a refusal arrives as a failure with the sentence`() = runBlocking {
        val runner = JobRunner(this)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { runner.outcomes.first() } }
        CorrectedCopyJob.launch(runner, CorrectedCopyRequest(ColourVisionKit.asset(), key), FakeHost({ photo() }, answers = listOf(false)), FakeSink())
        val error = outcome.await().result.exceptionOrNull()
        assertTrue(error is CorrectedCopyFailure)
        assertEquals(VisionCopy.COPY_PROTECTED, error!!.message)
    }

    @Test
    fun `cancelling the running job from the runner ends it as cancelled with no file left`() = runBlocking {
        val runner = JobRunner(this)
        val sink = FakeSink(onSave = { runner.cancelAll() })
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { runner.outcomes.first() } }
        CorrectedCopyJob.launch(runner, CorrectedCopyRequest(ColourVisionKit.asset(), key), FakeHost({ photo() }), sink)
        val error = outcome.await().result.exceptionOrNull()
        assertTrue("got $error", error is CancellationException)
        assertEquals(listOf("content://media/new/1"), sink.discarded.toList())
    }
}
