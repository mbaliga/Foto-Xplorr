package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.jobs.JobRunner
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A [BoardSink] that keeps what it was given, and fails or looks the way a test says. */
internal class FakeBoardSink : BoardSink {
    class Published(
        val width: Int,
        val height: Int,
        val topLeft: Int,
        val format: OutputFormat,
        val quality: Int,
        val baseName: String,
        val dateTakenMillis: Long,
        val bitmap: Bitmap,
    )

    val published: MutableList<Published> = Collections.synchronizedList(ArrayList())
    val progress: MutableList<Float> = Collections.synchronizedList(ArrayList())

    @Volatile var failWith: Throwable? = null

    /** The bitmap of the latest ask, so a test can see it was freed after a failure or a cancel. */
    @Volatile var seen: Bitmap? = null

    /** Run inside [publish], just before the sink's own look at `stillAllowed`. */
    @Volatile var beforeLook: suspend () -> Unit = {}

    /** A careless sink that never asks `stillAllowed`: the job's own look must then be the one that catches it. */
    @Volatile var ignoresLook = false

    override suspend fun publish(
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int,
        baseName: String,
        dateTakenMillis: Long,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): BoardPublished {
        seen = bitmap
        onProgress(0f)
        failWith?.let { throw it }
        beforeLook()
        currentCoroutineContext().ensureActive()
        if (!ignoresLook && !stillAllowed()) throw BoardProtectedException()
        onProgress(1f)
        published += Published(bitmap.width, bitmap.height, bitmap.getPixel(0, 0), format, quality, baseName, dateTakenMillis, bitmap)
        return BoardPublished("content://fake/${published.size}", "$baseName.${format.extension}")
    }
}

/**
 * The export job with the real renderer over flat-colour photos and a fake sink: what is refused, what
 * is saved and as what, what happens to a photo that is protected at the last moment, and that a cancel
 * leaves nothing. The Android writer is covered in [BoardWriterTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BoardExportTest {

    private val canvas = CanvasSize(200, 100)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun colourBoard(background: BoardBackground = BoardBackground.Colour(BoardTestKit.WHITE)) =
        Board(canvas, background, listOf(layer(1, 100f, 50f, 200f, 100f, content = LayerContent.Colour(RED))), 2)

    private fun photoBoard() = Board(
        canvas, BoardBackground.Colour(BoardTestKit.WHITE),
        listOf(layer(1, 100f, 50f, 200f, 100f, content = photo(1, 200, 100)), layer(2, 100f, 50f, 100f, 50f, content = photo(2, 200, 100))),
        3,
    )

    private fun run(
        board: Board,
        source: FakePhotoSource = FakePhotoSource(mapOf(1L to BLUE, 2L to RED)),
        sink: FakeBoardSink = FakeBoardSink(),
        format: OutputFormat = OutputFormat.JPEG,
        maxEdge: MaxEdgePreset = MaxEdgePreset.ORIGINAL,
        progress: MutableList<Float?> = ArrayList(),
    ): BoardExportOutcome = runBlocking {
        BoardExportJob.run(BoardExportRequest(board, "Spring", format, maxEdge), source, sink, { 5_000L }) { progress += it }
    }

    private fun failed(o: BoardExportOutcome) = o as BoardExportOutcome.Failed

    // ---- what is saved ----

    @Test
    fun `a board is drawn at its own size and saved once as a JPEG named from the board`() {
        val sink = FakeBoardSink()
        val o = run(colourBoard(), sink = sink) as BoardExportOutcome.Done
        val p = sink.published.single()
        assertEquals(200, p.width)
        assertEquals(100, p.height)
        assertEquals(RED, p.topLeft)
        assertEquals(OutputFormat.JPEG, p.format)
        assertEquals("Spring", p.baseName)
        assertEquals("the save is dated now", 5_000L, p.dateTakenMillis)
        assertEquals(200, o.width)
        assertEquals(100, o.height)
        assertEquals("Spring.jpg", o.displayName)
        assertFalse(o.steppedDown)
    }

    @Test
    fun `the bitmap is recycled once it is saved`() {
        val sink = FakeBoardSink()
        run(colourBoard(), sink = sink)
        assertTrue(sink.published.single().bitmap.isRecycled)
    }

    @Test
    fun `a see-through board is PNG whatever was asked for`() {
        val sink = FakeBoardSink()
        run(colourBoard(BoardBackground.Transparent), sink = sink, format = OutputFormat.JPEG)
        assertEquals(OutputFormat.PNG, sink.published.single().format)
        val half = FakeBoardSink()
        run(colourBoard(BoardBackground.Colour(0x80FFFFFF.toInt())), sink = half, format = OutputFormat.JPEG)
        assertEquals("half see-through counts", OutputFormat.PNG, half.published.single().format)
    }

    @Test
    fun `an opaque board is saved in the type asked for`() {
        val png = FakeBoardSink()
        run(colourBoard(), sink = png, format = OutputFormat.PNG)
        assertEquals(OutputFormat.PNG, png.published.single().format)
        val webp = FakeBoardSink()
        run(colourBoard(), sink = webp, format = OutputFormat.WEBP)
        assertEquals("a type the board does not offer becomes JPEG", OutputFormat.JPEG, webp.published.single().format)
    }

    @Test
    fun `a size cap shrinks the picture and keeps the shape`() {
        val big = Board(CanvasSize(4000, 2000), BoardBackground.Colour(BoardTestKit.WHITE), listOf(layer(1, 2000f, 1000f, 4000f, 2000f)), 2)
        val sink = FakeBoardSink()
        run(big, sink = sink, maxEdge = MaxEdgePreset.SMALL)
        assertEquals(1024, sink.published.single().width)
        assertEquals(512, sink.published.single().height)
    }

    @Test
    fun `progress rises from nothing to done and never goes backwards`() {
        val progress = ArrayList<Float?>()
        run(photoBoard(), progress = progress)
        assertEquals("indeterminate while it starts", null, progress.first())
        val numbers = progress.filterNotNull()
        assertTrue(numbers.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
        assertTrue("drawing is not the whole job", numbers.any { it in 0.1f..0.8f })
        assertEquals(1f, numbers.last(), 1e-6f)
    }

    // ---- refusals, before anything is drawn ----

    @Test
    fun `an empty board is refused and nothing is touched`() {
        val sink = FakeBoardSink()
        val source = FakePhotoSource()
        val o = run(Board(canvas), source, sink)
        assertEquals(BoardExportProblem.EMPTY, failed(o).problem)
        assertTrue(sink.published.isEmpty())
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a photo that cannot be used refuses the export before a single photo is decoded`() {
        val sink = FakeBoardSink()
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED), available = { it.mediaId != 2L })
        val f = failed(run(photoBoard(), source, sink))
        assertEquals(BoardExportProblem.PHOTOS_UNAVAILABLE, f.problem)
        assertEquals("it names the layer", listOf(2L), f.layerIds)
        assertTrue("nothing was decoded, so no protected pixel was read", source.calls.isEmpty())
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a photo that is off the canvas does not hold the export up`() {
        val off = Board(canvas, BoardBackground.Colour(BoardTestKit.WHITE), listOf(layer(1, 5000f, 5000f, 100f, 100f, content = photo(9, 200, 100)), layer(2, 100f, 50f, 50f, 50f)), 3)
        val sink = FakeBoardSink()
        run(off, FakePhotoSource(available = { false }), sink)
        assertEquals(1, sink.published.size)
    }

    // ---- protected at the last moment ----

    @Test
    fun `a photo that becomes protected while the board is drawn means nothing is saved`() {
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED))
        source.onDecoded = { if (source.calls.size == 2) source.available = { it.mediaId == 2L } }
        val sink = FakeBoardSink()
        val f = failed(run(photoBoard(), source, sink))
        assertEquals(BoardExportProblem.PROTECTED, f.problem)
        assertEquals(listOf(1L), f.layerIds)
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `the job checks again itself when the sink does not`() {
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED))
        source.onDecoded = { if (source.calls.size == 2) source.available = { false } }
        val sink = FakeBoardSink().apply { ignoresLook = true }
        assertEquals(BoardExportProblem.PROTECTED, failed(run(photoBoard(), source, sink)).problem)
        assertTrue("a careless sink was never reached", sink.published.isEmpty())
    }

    @Test
    fun `a photo that becomes protected right before the file is made is refused by the sink`() {
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED))
        val sink = FakeBoardSink().apply { beforeLook = { source.available = { false } } }
        assertEquals(BoardExportProblem.PROTECTED, failed(run(photoBoard(), source, sink)).problem)
        assertTrue(sink.published.isEmpty())
    }

    // ---- failures ----

    @Test
    fun `a sink that fails is a save failure, a full phone is said so, and memory is said so`() {
        assertEquals(BoardExportProblem.SAVE_FAILED, failed(run(colourBoard(), sink = FakeBoardSink().apply { failWith = IOException("x") })).problem)
        assertEquals(BoardExportProblem.NO_SPACE, failed(run(colourBoard(), sink = FakeBoardSink().apply { failWith = BoardNoSpaceException() })).problem)
        assertEquals(BoardExportProblem.TOO_BIG, failed(run(colourBoard(), sink = FakeBoardSink().apply { failWith = OutOfMemoryError("test") })).problem)
        assertEquals(BoardExportProblem.SAVE_FAILED, failed(run(colourBoard(), sink = FakeBoardSink().apply { failWith = SecurityException("no") })).problem)
    }

    @Test
    fun `a photo that cannot be decoded halfway is a refusal with no file`() {
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED))
        // isAvailable says yes, the decode itself finds nothing (the file went between the two).
        source.available = { true }
        val flaky = object : BoardPhotoSource by source {
            override suspend fun decode(ref: PhotoRef, limits: com.fotoxplorr.app.media.DecodeLimits): Bitmap? =
                if (ref.mediaId == 2L) null else source.decode(ref, limits)
        }
        val sink = FakeBoardSink()
        val f = failed(runBlocking { BoardExportJob.run(BoardExportRequest(photoBoard(), "x"), flaky, sink) })
        assertEquals(BoardExportProblem.PHOTOS_UNAVAILABLE, f.problem)
        assertTrue(sink.published.isEmpty())
    }

    // ---- cancel ----

    @Test
    fun `a cancel while the sink is working saves nothing, throws, and frees the bitmap`() {
        val sink = FakeBoardSink()
        sink.beforeLook = { currentCoroutineContext().cancel() }
        var thrown: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    BoardExportJob.run(BoardExportRequest(colourBoard(), "x"), FakePhotoSource(), sink)
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        assertTrue(sink.published.isEmpty())
        assertTrue("the picture is freed on a cancel", sink.seen!!.isRecycled)
    }

    @Test
    fun `a cancel while the photos are being drawn stops before the sink`() {
        val source = FakePhotoSource(mapOf(1L to BLUE, 2L to RED))
        val sink = FakeBoardSink()
        runBlocking {
            val job = launch {
                BoardExportJob.run(BoardExportRequest(photoBoard(), "x"), source, sink)
            }
            source.onDecoded = { job.cancel() }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(sink.published.isEmpty())
    }

    // ---- as a job ----

    @Test
    fun `launched on the runner it reports a success and a failure the way the screen reads them`() {
        val runner = JobRunner(scope)
        val ok = runBlocking {
            val outcome = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            val handle = BoardExportJob.launch(runner, BoardExportRequest(colourBoard(), "Spring"), FakePhotoSource(), FakeBoardSink())
            val got = withTimeout(60_000) { outcome.await() }
            assertEquals(handle.id, got.id)
            got
        }
        assertEquals(BoardCopy.JOB_TITLE, ok.title)
        assertTrue(ok.result.getOrThrow() is BoardExportOutcome.Done)

        val bad = runBlocking {
            val outcome = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            BoardExportJob.launch(runner, BoardExportRequest(Board(canvas), "x"), FakePhotoSource(), FakeBoardSink())
            withTimeout(60_000) { outcome.await() }
        }
        val failure = bad.result.exceptionOrNull() as BoardExportFailure
        assertEquals(BoardExportProblem.EMPTY, failure.problem)
        assertEquals(BoardCopy.problem(BoardExportProblem.EMPTY), failure.message)
    }

    @Test
    fun `a running job can be cancelled from the runner and leaves no file`() {
        val runner = JobRunner(scope)
        val sink = FakeBoardSink()
        val outcome = runBlocking {
            val got = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            sink.beforeLook = { runner.jobs.value.single().cancel() }
            BoardExportJob.launch(runner, BoardExportRequest(colourBoard(), "x"), FakePhotoSource(), sink)
            withTimeout(60_000) { got.await() }
        }
        assertTrue(outcome.result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(sink.published.isEmpty())
    }

    // ---- pure: file names and types ----

    @Test
    fun `a file name is the board's name with the type's extension`() {
        assertEquals("Spring.png", BoardExportJob.fileName("Spring", OutputFormat.PNG) { false })
        assertEquals("Spring.jpg", BoardExportJob.fileName("Spring", OutputFormat.JPEG) { false })
    }

    @Test
    fun `a name that is taken gets a number and never replaces`() {
        val taken = setOf("Spring.png", "Spring-2.png", "Spring-3.png")
        assertEquals("Spring-4.png", BoardExportJob.fileName("Spring", OutputFormat.PNG) { it in taken })
        assertEquals("Spring.jpg", BoardExportJob.fileName("Spring", OutputFormat.JPEG) { it in taken })
    }

    @Test
    fun `a name no one can have after a thousand tries`() {
        val name = BoardExportJob.fileName("Spring", OutputFormat.PNG) { true }
        assertTrue(name.startsWith("Spring-") && name.endsWith(".png"))
    }

    @Test
    fun `a name is made safe for a file system`() {
        assertEquals("a_b_c.png", BoardExportJob.fileName("a/b\\c", OutputFormat.PNG) { false })
        assertEquals("what_.png", BoardExportJob.fileName("what?", OutputFormat.PNG) { false })
        assertEquals("${BoardCopy.DEFAULT_NAME}.png", BoardExportJob.fileName("   ", OutputFormat.PNG) { false })
        assertEquals("${BoardCopy.DEFAULT_NAME}.png", BoardExportJob.fileName("...", OutputFormat.PNG) { false })
        assertEquals("ab.png", BoardExportJob.fileName("a\nb", OutputFormat.PNG) { false })
        assertTrue(BoardExportJob.fileName("x".repeat(500), OutputFormat.PNG) { false }.length <= 84)
    }

    private fun listed(id: Long, uri: String = "content://media/external/images/media/$id") = com.fotoxplorr.app.media.MediaAsset(
        id = com.fotoxplorr.core.model.MediaId(id), contentUriString = uri, displayName = "x.png", mimeType = "image/png",
        bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0, width = 1, height = 1, sizeBytes = 1,
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    @Test
    fun `a saved picture is found in the gallery's list by its row number, however the volume is spelled`() {
        val list = listOf(listed(5), listed(9))
        assertEquals(9L, BoardExportJob.savedAsset(list, "content://media/external_primary/images/media/9")!!.id.value)
        assertEquals(5L, BoardExportJob.savedAsset(list, "content://media/external/images/media/5")!!.id.value)
        assertNull(BoardExportJob.savedAsset(list, "content://media/external/images/media/6"))
        assertNull(BoardExportJob.savedAsset(list, ""))
        assertNull(BoardExportJob.savedAsset(emptyList(), "content://media/external/images/media/5"))
    }

    @Test
    fun `the size an export would be is told before saving`() {
        val b = colourBoard()
        assertEquals(200, BoardExportJob.outputSize(b, MaxEdgePreset.ORIGINAL).width)
        val big = Board(CanvasSize(4000, 2000), BoardBackground.Colour(BoardTestKit.WHITE), listOf(layer(1, 2000f, 1000f, 4000f, 2000f)), 2)
        val small = BoardExportJob.outputSize(big, MaxEdgePreset.SMALL)
        assertEquals(1024, small.width)
        assertEquals(512, small.height)
    }

    @Test
    fun `the types on offer are PNG and JPEG and a see-through board has no choice`() {
        assertEquals(listOf(OutputFormat.PNG, OutputFormat.JPEG), BoardFormats.CHOICES)
        assertEquals(OutputFormat.JPEG, BoardFormats.defaultFor(colourBoard()))
        assertEquals(OutputFormat.PNG, BoardFormats.defaultFor(colourBoard(BoardBackground.Transparent)))
        assertTrue(BoardFormats.isChoosable(colourBoard()))
        assertFalse(BoardFormats.isChoosable(colourBoard(BoardBackground.Transparent)))
    }
}
