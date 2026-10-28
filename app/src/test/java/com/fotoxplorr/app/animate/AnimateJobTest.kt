package com.fotoxplorr.app.animate

import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The animation job end to end on procedural pictures: a fake library, a fake sink and a fake video
 * maker, with the real planner, scaler, GIF and APNG encoders and decoders. The job's contract is
 * checked here: what it plans, progress, cancel, what it says when it cannot, what it refuses, and
 * the way down when memory or the size cap says no. Nothing about a phone's codecs or speed is.
 */
class AnimateJobTest {

    private fun stills(n: Int, hold: Int = 200, transition: StillTransition? = null) =
        AnimateInput.Stills(List(n) { AnimStill(AnimateKit.asset(it + 1L), hold) }, transition)

    private fun request(
        input: AnimateInput,
        output: AnimOutput = AnimOutput.GIF,
        limits: PlanLimits = AnimateDefaults.limits(output),
        targetFps: Double? = null,
        keepTransparency: Boolean = false,
        plays: Int? = null,
        background: Int = AnimateRequest.OPAQUE_BLACK,
    ) = AnimateRequest(input, output, limits, targetFps, background = background, keepTransparency = keepTransparency, plays = plays)

    private fun run(
        request: AnimateRequest,
        host: FakeAnimateHost = FakeAnimateHost(),
        sink: FakeAnimateSink = FakeAnimateSink(),
        maker: FakeVideoMaker? = null,
        heap: Long = 1L shl 30,
        progress: MutableList<Float>? = null,
    ): AnimateOutcome = runBlocking {
        AnimateJob.run(request, host, sink, maker, { heap }) { progress?.add(it) }
    }

    private fun failed(outcome: AnimateOutcome): String {
        assertTrue("expected a failure, got a file", outcome is AnimateOutcome.Failed)
        return (outcome as AnimateOutcome.Failed).message
    }

    private fun done(outcome: AnimateOutcome): AnimateOutcome.Done {
        assertTrue("expected a file, got: ${(outcome as? AnimateOutcome.Failed)?.message}", outcome is AnimateOutcome.Done)
        return outcome as AnimateOutcome.Done
    }

    // ---- the happy paths ----------------------------------------------------------------------------

    @Test
    fun `a slideshow becomes one GIF with a frame for each still, saved once`() {
        val host = FakeAnimateHost()
        val sink = FakeAnimateSink()
        val result = done(run(request(stills(5, hold = 300), plays = 3), host, sink))
        assertEquals(1, sink.published.size)
        val saved = sink.published.single()
        assertEquals(AnimOutput.GIF, saved.output)
        assertEquals("content://fake/published/1", result.uri)
        assertEquals(AnimateCopy.SAVED, result.message)
        val decoded = GifReader.decodeAll(saved.bytes)
        assertEquals(DecodeProblem.NONE, decoded.problem)
        val seq = decoded.sequence!!
        assertEquals(5, seq.frames.size)
        assertEquals(result.frames, seq.frames.size)
        assertEquals(480, seq.width)
        assertEquals(360, seq.height)
        assertEquals(result.width, seq.width)
        assertEquals("the plays asked for", 3, seq.plays)
        assertEquals(saved.bytes.size.toLong(), result.bytes)
        assertEquals("5 stills of 300 ms", 1500L, result.playMs)
        assertFalse(result.steppedDown)
        assertEquals("the first photo's date", AnimateKit.asset(1).dateTakenMillis, saved.dateTakenMillis)
        assertEquals("named after the first photo", "IMG_0001.jpg", saved.sourceName)
        assertTrue("working files are gone: ${sink.leftovers()}", sink.leftovers().isEmpty())
    }

    @Test
    fun `a slideshow loops for ever unless told otherwise`() {
        val sink = FakeAnimateSink()
        done(run(request(stills(3)), sink = sink))
        assertEquals(0, GifReader.decodeAll(sink.published.single().bytes).sequence!!.plays)
    }

    @Test
    fun `the planner's ceilings are honoured and what it gave up is told`() {
        val sink = FakeAnimateSink()
        val limits = PlanLimits(maxLongEdge = 100, maxFrames = 6)
        val result = done(run(request(stills(12), limits = limits), sink = sink))
        assertEquals(6, result.frames)
        assertEquals(100, result.width)
        assertEquals(75, result.height)
        val seq = GifReader.decodeAll(sink.published.single().bytes).sequence!!
        assertEquals(6, seq.frames.size)
        assertEquals(100, seq.width)
        assertTrue(AnimateCopy.planStep(PlanStep.FRAMES_THINNED) in result.notes)
        assertFalse("the planner's last-resort note is never shown for a file that fit", AnimateCopy.STILL_TOO_BIG in result.notes)
    }

    @Test
    fun `a lowered rate keeps the length`() {
        val sink = FakeAnimateSink()
        // 20 stills of 50 ms (a second) at 10 fps.
        val result = done(run(request(stills(20, hold = 50), targetFps = 10.0), sink = sink))
        assertEquals(10, result.frames)
        assertEquals(1000L, result.playMs)
        assertTrue(AnimateCopy.planStep(PlanStep.RATE_LOWERED) in result.notes)
    }

    @Test
    fun `an APNG is lossless and has the same frames`() {
        val host = FakeAnimateHost()
        val sink = FakeAnimateSink()
        val result = done(run(request(stills(4, hold = 250), AnimOutput.APNG, plays = 2), host, sink))
        val saved = sink.published.single()
        assertEquals(AnimOutput.APNG, saved.output)
        val seq = ApngReader.decodeAll(saved.bytes).sequence!!
        assertEquals(4, seq.frames.size)
        assertEquals(2, seq.plays)
        for (i in 0 until 4) {
            val expect = AnimateKit.picture(i + 1, seq.width, seq.height).argb
            assertEquals("frame $i, first pixel", expect[0], seq.frames[i].pixels[0])
            assertEquals("frame $i, last pixel", expect.last(), seq.frames[i].pixels.last())
        }
        assertEquals(1000L, result.playMs)
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `a video is made from frame pictures, even sized, at a whole rate, with a bitrate`() {
        val host = FakeAnimateHost()
        host.stillSize = { 641 to 481 }
        val sink = FakeAnimateSink()
        val maker = FakeVideoMaker()
        val result = done(run(request(stills(3, hold = 2000), AnimOutput.VIDEO), host, sink, maker))
        val made = maker.made.single()
        assertEquals(0, made.width % 2)
        assertEquals(0, made.height % 2)
        assertEquals(result.width, made.width)
        assertEquals(30, made.fps)
        assertEquals("three stills held two seconds each", listOf(60, 60, 60), made.frames.map { it.ticks })
        assertTrue(made.bitrate >= 400_000)
        assertEquals(6000L, result.playMs)
        assertEquals(3, maker.written.size)
        assertTrue(maker.written.all { it.first == made.width && it.second == made.height })
        assertEquals(AnimOutput.VIDEO, sink.published.single().output)
        assertTrue("the frame pictures and the staged video are gone: ${sink.leftovers()}", sink.leftovers().isEmpty())
    }

    @Test
    fun `a video is not thinned by the size rates the planner uses for GIF and PNG`() {
        val maker = FakeVideoMaker()
        // A cap of 1000 bytes would thin 40 frames of 480 x 360 to a handful if the planner's GIF rates decided; the "video" here is 56 bytes.
        val limits = PlanLimits(maxLongEdge = 480, maxFrames = 600, maxBytes = 1000)
        val result = done(run(request(stills(40, hold = 100), AnimOutput.VIDEO, limits = limits), sink = FakeAnimateSink(), maker = maker))
        assertEquals(40, result.frames)
        assertEquals(480, result.width)
        assertFalse(result.steppedDown)
    }

    @Test
    fun `a clip is sampled in order along its ticks`() {
        val reader = FakeVideoReader()
        val host = FakeAnimateHost().apply { videos[9L] = reader }
        val sink = FakeAnimateSink()
        val input = AnimateInput.Clip(AnimateKit.video(9), 1000, 2000, 10.0)
        val result = done(run(request(input), host, sink))
        assertEquals(10, result.frames)
        val times = reader.times.sorted()
        assertEquals((0 until 10).map { 1000L + it * 100 }, times)
        assertTrue(reader.closed)
        assertEquals(DecodeProblem.NONE, GifReader.decodeAll(sink.published.single().bytes).problem)
        assertEquals("named after the video", "VID_0009.mp4", sink.published.single().sourceName)
    }

    @Test
    fun `an existing GIF is remade as an APNG with the same frames and its own plays`() {
        val seq = AnimTestKit.photoSequence(40, 30, 6, 90, noise = 0, plays = 4)
        val file = GifEncoder.encode(seq, GifOptions(ditherStrength = 0f))
        val host = FakeAnimateHost().apply { files[7L] = file }
        val sink = FakeAnimateSink()
        val input = AnimateInput.Existing(AnimateKit.asset(7, "loop.gif", "image/gif"))
        val result = done(run(request(input, AnimOutput.APNG), host, sink))
        val back = ApngReader.decodeAll(sink.published.single().bytes).sequence!!
        val reference = GifReader.decodeAll(file).sequence!!
        assertEquals(6, back.frames.size)
        assertEquals(4, back.plays)
        for (i in 0 until 6) assertEquals(reference.frames[i].pixels.toList(), back.frames[i].pixels.toList())
        assertEquals("loop.gif", sink.published.single().sourceName)
        assertEquals(40, result.width)
    }

    @Test
    fun `an existing animation is made smaller by the planner`() {
        val seq = AnimTestKit.photoSequence(200, 150, 8, 100, noise = 0)
        val host = FakeAnimateHost().apply { files[7L] = GifEncoder.encode(seq) }
        val sink = FakeAnimateSink()
        val input = AnimateInput.Existing(AnimateKit.asset(7, "big.gif", "image/gif"))
        val result = done(run(request(input, limits = PlanLimits(maxLongEdge = 100)), host, sink))
        assertEquals(100, result.width)
        assertEquals(75, result.height)
        assertEquals(100, GifReader.decodeAll(sink.published.single().bytes).sequence!!.width)
        assertTrue(AnimateCopy.planStep(PlanStep.SHRUNK) in result.notes)
    }

    @Test
    fun `see-through pixels are kept when asked, and drawn over the background when not`() {
        val sticker = AnimTestKit.stickerSequence(32, 24, 4)
        val host = FakeAnimateHost().apply { files[7L] = ApngEncoder.encode(sticker) }
        val input = AnimateInput.Existing(AnimateKit.asset(7, "sticker.png", "image/png"))

        val keep = FakeAnimateSink()
        done(run(request(input, AnimOutput.APNG, keepTransparency = true), host, keep))
        val kept = ApngReader.decodeAll(keep.published.single().bytes).sequence!!
        assertTrue("clear pixels survive", kept.hasTransparency)

        val keepGif = FakeAnimateSink()
        done(run(request(input, AnimOutput.GIF, keepTransparency = true), host, keepGif))
        assertTrue(GifReader.decodeAll(keepGif.published.single().bytes).sequence!!.hasTransparency)

        val flat = FakeAnimateSink()
        done(run(request(input, AnimOutput.GIF, background = 0xFF204060.toInt()), host, flat))
        val flatSeq = GifReader.decodeAll(flat.published.single().bytes).sequence!!
        assertFalse(flatSeq.hasTransparency)
        val corner = flatSeq.frames[0].pixels[0]
        for (shift in listOf(16, 8, 0)) assertEquals("the background, to the palette's accuracy", ((0xFF204060.toInt() shr shift) and 255).toDouble(), ((corner shr shift) and 255).toDouble(), 3.0)
    }

    @Test
    fun `a damaged source is used as far as it goes and the person is told`() {
        val seq = AnimTestKit.photoSequence(40, 30, 8, 80, noise = 0)
        val host = FakeAnimateHost().apply { files[7L] = AnimateKit.cutGif(GifEncoder.encode(seq)) }
        val sink = FakeAnimateSink()
        val result = done(run(request(AnimateInput.Existing(AnimateKit.asset(7, "cut.gif", "image/gif"))), host, sink))
        assertTrue(result.frames in 2..7)
        assertTrue(AnimateCopy.TRUNCATED in result.notes)
    }

    // ---- progress ------------------------------------------------------------------------------------------

    @Test
    fun `progress rises in steps to 1 for every output`() {
        for (output in AnimOutput.values()) {
            val progress = Collections.synchronizedList(ArrayList<Float>())
            done(run(request(stills(40, hold = 100), output), maker = FakeVideoMaker(), progress = progress))
            val shown = progress.toList()
            assertEquals("$output ends at 1", 1f, shown.last(), 0f)
            assertEquals("$output starts at 0", 0f, shown.first(), 0f)
            assertTrue("$output only rises: $shown", shown.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("$output has steps in between, not a jump: ${shown.size}", shown.size >= 10)
            assertTrue(shown.all { it in 0f..1f })
        }
    }

    // ---- what it refuses -------------------------------------------------------------------------------------

    @Test
    fun `fewer than two stills is refused before anything is read`() {
        val host = FakeAnimateHost()
        assertEquals(AnimateCopy.NEED_PICTURES, failed(run(request(stills(1)), host)))
        assertEquals(AnimateCopy.NEED_PICTURES, failed(run(request(stills(0)), host)))
        assertTrue(host.stillCalls.isEmpty())
    }

    @Test
    fun `a protected photo is refused before any pixel is read`() {
        val host = FakeAnimateHost().apply { protectedIds += 3L }
        val sink = FakeAnimateSink()
        assertEquals(AnimateCopy.PROTECTED, failed(run(request(stills(5)), host, sink)))
        assertTrue("not even a probe", host.stillCalls.isEmpty())
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `a protected video is refused before it is opened`() {
        val reader = FakeVideoReader()
        val host = FakeAnimateHost().apply { videos[9L] = reader; protectedIds += 9L }
        val input = AnimateInput.Clip(AnimateKit.video(9), 0, 1000)
        assertEquals(AnimateCopy.PROTECTED, failed(run(request(input), host)))
        assertTrue(reader.times.isEmpty())
    }

    @Test
    fun `a protected animated file is refused before it is read`() {
        val host = FakeAnimateHost().apply { files[7L] = ByteArray(10); protectedIds += 7L }
        assertEquals(AnimateCopy.PROTECTED, failed(run(request(AnimateInput.Existing(AnimateKit.asset(7))), host)))
    }

    @Test
    fun `a photo protected while the job runs is refused at its next read`() {
        val host = FakeAnimateHost()
        host.onStill = { asset, _ -> if (asset.id.value == 4L) host.protectedIds += 2L }
        val sink = FakeAnimateSink()
        assertEquals(AnimateCopy.PROTECTED, failed(run(request(stills(6)), host, sink)))
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `the job looks once more right before it saves, and the sink looks again`() {
        // A video: the world changes while the video is being made, after the last pixel was read.
        val host = FakeAnimateHost()
        val maker = FakeVideoMaker().apply { onMake = { host.protectedIds += 2L } }
        val sink = FakeAnimateSink().apply { ignoresLook = true }
        assertEquals(AnimateCopy.PROTECTED, failed(run(request(stills(3), AnimOutput.VIDEO), host, sink, maker)))
        assertTrue("the job's own look caught it though the sink did not ask", sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())

        val host2 = FakeAnimateHost()
        val sink2 = FakeAnimateSink().apply { beforeLook = { host2.protectedIds += 1L } }
        assertEquals("the sink's look, after the job's", AnimateCopy.PROTECTED, failed(run(request(stills(3)), host2, sink2)))
        assertTrue(sink2.published.isEmpty())
        assertTrue(sink2.leftovers().isEmpty())
    }

    @Test
    fun `a video without an encoder, or without a maker, is refused`() {
        val host = FakeAnimateHost().apply { encoder = false }
        assertEquals(AnimateCopy.NO_VIDEO_ENCODER, failed(run(request(stills(3), AnimOutput.VIDEO), host, maker = FakeVideoMaker())))
        assertEquals(AnimateCopy.NO_VIDEO_ENCODER, failed(run(request(stills(3), AnimOutput.VIDEO), FakeAnimateHost(), maker = null)))
        assertTrue(host.stillCalls.isEmpty())
    }

    // ---- what it says when a source cannot be read --------------------------------------------------------------

    @Test
    fun `a photo that cannot be read is named by its place`() {
        val host = FakeAnimateHost().apply { unreadable += 3L }
        assertEquals(AnimateCopy.cannotRead(3), failed(run(request(stills(5)), host)))
        val first = FakeAnimateHost().apply { unreadable += 1L }
        assertEquals(AnimateCopy.cannotRead(1), failed(run(request(stills(5)), first)))
    }

    @Test
    fun `a file that is not an animation, or cannot be read, says so`() {
        val host = FakeAnimateHost().apply { files[7L] = "plain text".toByteArray() }
        assertEquals(AnimateCopy.NOT_ANIMATED, failed(run(request(AnimateInput.Existing(AnimateKit.asset(7))), host)))
        assertEquals(AnimateCopy.FILE_UNREADABLE, failed(run(request(AnimateInput.Existing(AnimateKit.asset(8))), host)))
    }

    @Test
    fun `an unreadable video says so`() {
        val input = AnimateInput.Clip(AnimateKit.video(9), 0, 1000)
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, failed(run(request(input), FakeAnimateHost())))
    }

    @Test
    fun `a failure while saving is its own sentence, and a full phone another`() {
        val sink = FakeAnimateSink().apply { failWith = IOException("disk on fire") }
        assertEquals(AnimateCopy.SAVE_FAILED, failed(run(request(stills(3)), sink = sink)))
        assertTrue(sink.leftovers().isEmpty())
        val full = FakeAnimateSink().apply { failWith = AnimateNoSpaceException() }
        assertEquals(AnimateCopy.NO_SPACE, failed(run(request(stills(3)), sink = full)))
        val enospc = FakeAnimateSink().apply { failWith = IOException("write failed", IOException("ENOSPC (No space left on device)")) }
        assertEquals(AnimateCopy.NO_SPACE, failed(run(request(stills(3)), sink = enospc)))
        val boom = FakeAnimateSink().apply { failWith = IllegalStateException("boom") }
        assertEquals(AnimateCopy.FAILED, failed(run(request(stills(3)), sink = boom)))
    }

    @Test
    fun `a video that cannot be made says the maker's sentence`() {
        val maker = FakeVideoMaker().apply { failWith = VideoMakeException(AnimateCopy.VIDEO_FAILED) }
        val sink = FakeAnimateSink()
        assertEquals(AnimateCopy.VIDEO_FAILED, failed(run(request(stills(3), AnimOutput.VIDEO), sink = sink, maker = maker)))
        assertTrue("no frame pictures or half-made video left: ${sink.leftovers()}", sink.leftovers().isEmpty())
        val noEncoder = FakeVideoMaker().apply { failWith = VideoMakeException(AnimateCopy.NO_VIDEO_ENCODER) }
        assertEquals(AnimateCopy.NO_VIDEO_ENCODER, failed(run(request(stills(3), AnimOutput.VIDEO), maker = noEncoder)))
    }

    // ---- cancel ----------------------------------------------------------------------------------------------

    @Test
    fun `cancelling mid-way leaves no row and no working file`() {
        for (output in AnimOutput.values()) {
            val host = FakeAnimateHost()
            val sink = FakeAnimateSink()
            val maker = FakeVideoMaker()
            val calls = AtomicInteger(0)
            runBlocking {
                lateinit var job: kotlinx.coroutines.Job
                job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                    AnimateJob.run(request(stills(30), output), host, sink, maker)
                }
                host.onStill = { _, _ -> if (calls.incrementAndGet() == 10) job.cancel() }
                job.start()
                withTimeout(30_000) { job.join() }
                assertTrue("$output: the caller sees a cancel", job.isCancelled)
            }
            assertTrue("$output: no row", sink.published.isEmpty())
            assertTrue("$output: no working file: ${sink.leftovers()}", sink.leftovers().isEmpty())
            assertTrue("$output: the job stopped reading: ${calls.get()}", calls.get() < 30)
        }
    }

    @Test
    fun `cancelling while a video is being made leaves nothing`() {
        val sink = FakeAnimateSink()
        val maker = FakeVideoMaker()
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                AnimateJob.run(request(stills(4), AnimOutput.VIDEO), FakeAnimateHost(), sink, maker)
            }
            maker.onMake = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `run throws only a cancellation`() {
        val host = FakeAnimateHost()
        host.onStill = { _, _ -> throw CancellationException("stop") }
        try {
            run(request(stills(3)), host)
            fail("expected a cancellation")
        } catch (e: CancellationException) {
            // fine
        }
    }

    // ---- the way down ----------------------------------------------------------------------------------------

    @Test
    fun `an out of memory takes a smaller plan and says so`() {
        val host = FakeAnimateHost()
        // The probe (the first read) is fine; frames at 200 px or more run out of memory.
        host.onStill = { _, edge -> if (host.stillCalls.size > 1 && edge >= 200) throw OutOfMemoryError("test") }
        val sink = FakeAnimateSink()
        val result = done(run(request(stills(12), limits = PlanLimits(maxLongEdge = 240)), host, sink))
        assertTrue(result.steppedDown)
        assertTrue("width ${result.width}", result.width < 200)
        assertTrue(AnimateCopy.MEMORY_STEP in result.notes)
        assertEquals("one file", 1, sink.published.size)
        assertTrue(sink.leftovers().isEmpty())
        assertEquals(result.width, GifReader.decodeAll(sink.published.single().bytes).sequence!!.width)
    }

    @Test
    fun `an out of memory that never goes away ends in a sentence`() {
        val host = FakeAnimateHost()
        host.onStill = { _, _ -> if (host.stillCalls.size > 1) throw OutOfMemoryError("test") }
        val sink = FakeAnimateSink()
        assertEquals(AnimateCopy.OUT_OF_MEMORY, failed(run(request(stills(6)), host, sink)))
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `an out of memory while opening is a sentence, not a crash`() {
        val host = FakeAnimateHost().apply { oomAboveEdge = 10 }
        assertEquals(AnimateCopy.OUT_OF_MEMORY, failed(run(request(stills(3)), host)))
    }

    @Test
    fun `an APNG that would not fit the heap is made smaller before it is tried`() {
        val host = FakeAnimateHost()
        val sink = FakeAnimateSink()
        // 12 frames at 480 x 360 are 8 MB; a 12 MB heap allows 30 percent of it.
        val result = done(run(request(stills(12), AnimOutput.APNG), host, sink, heap = 12L * 1024 * 1024))
        assertTrue(result.steppedDown)
        assertTrue(AnimateCopy.MEMORY_STEP in result.notes)
        assertTrue(AnimChoices.sequenceFits(result.frames, result.width, result.height, 12L * 1024 * 1024))
        val frameReads = host.stillCalls.size
        assertTrue("it did not read frames for the attempts it knew would not fit: $frameReads", frameReads <= 1 + 12)
    }

    @Test
    fun `a heap too small for any APNG is a sentence`() {
        assertEquals(AnimateCopy.OUT_OF_MEMORY, failed(run(request(stills(6), AnimOutput.APNG), heap = 1024)))
    }

    @Test
    fun `a video over the size cap is remade smaller until it fits`() {
        val maker = FakeVideoMaker()
        // The "video" is a byte for every ten pixels of every frame.
        maker.fileBytes = { w, h, n -> (w.toLong() * h * n / 10).toInt() }
        val sink = FakeAnimateSink()
        val limits = PlanLimits(maxLongEdge = 480, maxFrames = 40, maxBytes = 200_000)
        val result = done(run(request(stills(40, hold = 100), AnimOutput.VIDEO, limits = limits), sink = sink, maker = maker))
        assertTrue(result.steppedDown)
        assertTrue(AnimateCopy.SIZE_STEP in result.notes)
        assertTrue("the saved file is under the cap: ${result.bytes}", result.bytes <= 200_000)
        assertTrue("more than one try", maker.made.size > 1)
        val sizes = maker.made.map { it.width.toLong() * it.height * it.frames.size }
        assertTrue("each try is smaller: $sizes", sizes.zipWithNext().all { (a, b) -> b < a })
        assertEquals(1, sink.published.size)
        assertTrue(sink.leftovers().isEmpty())
    }

    @Test
    fun `a file that cannot be made small enough is a sentence and leaves nothing`() {
        val sink = FakeAnimateSink()
        val result = run(request(stills(6), limits = PlanLimits(maxBytes = 50)), sink = sink)
        assertEquals(AnimateCopy.STILL_OVER_LIMIT, failed(result))
        assertTrue(sink.published.isEmpty())
        assertTrue(sink.leftovers().isEmpty())
    }

    // ---- the runner --------------------------------------------------------------------------------------------

    @Test
    fun `as a runner job the outcome is the file, a failure carries the sentence, and a cancel is a cancel`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val runner = JobRunner(scope)

            val ok = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            AnimateJob.launch(runner, request(stills(3)), FakeAnimateHost(), FakeAnimateSink())
            val success = withTimeout(30_000) { ok.await() }
            assertEquals(AnimateCopy.JOB_TITLE, success.title)
            assertTrue((success.result.getOrThrow() as AnimateOutcome.Done).frames == 3)

            val bad = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            AnimateJob.launch(runner, request(stills(1)), FakeAnimateHost(), FakeAnimateSink())
            val failure = withTimeout(30_000) { bad.await() }.result.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure is AnimateFailure)
            assertEquals(AnimateCopy.NEED_PICTURES, failure!!.message)

            val host = FakeAnimateHost()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { runner.outcomes.first() }
            val sink = FakeAnimateSink()
            val handle = AnimateJob.launch(runner, request(stills(30)), host.also { h -> h.onStill = { _, _ -> if (h.stillCalls.size == 3) gate.complete(Unit) } }, sink)
            withTimeout(30_000) { gate.await() }
            handle.cancel()
            val stopped = withTimeout(30_000) { cancelled.await() }.result.exceptionOrNull()
            assertTrue("a cancel, not a failure: $stopped", stopped is CancellationException)
            assertTrue(sink.published.isEmpty())
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `out of space is told from its cause chain`() {
        assertTrue(AnimateJob.isOutOfSpace(AnimateNoSpaceException()))
        assertTrue(AnimateJob.isOutOfSpace(IOException("write", IOException("ENOSPC (No space left on device)"))))
        assertTrue(AnimateJob.isOutOfSpace(IOException("no space left on device")))
        assertFalse(AnimateJob.isOutOfSpace(IOException("disk full")))
        assertFalse(AnimateJob.isOutOfSpace(null))
        assertFalse(AnimateJob.isOutOfSpace(RuntimeException("ENOSPC")))
    }
}
