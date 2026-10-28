package com.fotoxplorr.app.animate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The small looping preview made by the real frame sources, and the clock that decides which frame is up. */
class AnimatePreviewTest {
    private val photos = (1L..60L).map { AnimateKit.asset(it, width = 1600, height = 1200) }
    private val video = AnimateKit.video(500, durationMs = 20_000)
    private val gif = AnimateKit.asset(600, "party.gif", mime = "image/gif")
    private val library = AnimateLibrary(photos + video + gif)

    private fun ticked(n: Int): AnimateDraft = (1L..n).fold(AnimateDraft()) { d, id -> d.togglePhoto(id) }

    private fun request(d: AnimateDraft): AnimateRequest = d.toRequest(library)!!

    private fun make(d: AnimateDraft, host: FakeAnimateHost = FakeAnimateHost()): PreviewResult = runBlocking { AnimatePreview.make(request(d), host) }

    private fun ready(r: PreviewResult): PreviewSet = (r as? PreviewResult.Ready)?.set ?: throw AssertionError("expected a preview, got ${(r as PreviewResult.Failed).message}")

    // ---- photos ----------------------------------------------------------------------------------------------

    @Test
    fun `a slideshow preview is small, opaque, one frame a photo, and as long as the real thing`() {
        val d = ticked(5).select(1).holdBy(500)
        val host = FakeAnimateHost().apply { stillSize = { 1600 to 1200 } }
        val set = ready(make(d, host))
        assertEquals(240, set.width)
        assertEquals(180, set.height)
        assertEquals(5, set.frames.size)
        assertEquals(listOf(1000, 1500, 1000, 1000, 1000), set.frames.map { it.delayMs })
        assertTrue(set.frames.all { f -> f.argb.all { (it ushr 24) == 255 } })
        // Every photo was read at the preview's size, not the file's.
        assertTrue(host.stillCalls.all { it.edge <= AnimatePreview.EDGE })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), host.stillCalls.map { it.id }.distinct().sorted())
    }

    @Test
    fun `a long slideshow keeps its length with fewer frames`() {
        val d = ticked(20).withFade(true)
        val set = ready(make(d))
        assertTrue("frames ${set.frames.size}", set.frames.size <= AnimatePreview.MAX_FRAMES)
        val real = StillsTimeline.build(IntArray(20) { 1000 }, StillTransition(4, 60, loop = true)).delaysMs.sum()
        assertEquals(real.toDouble(), set.delaysMs.sum().toDouble(), 1.0)
    }

    @Test
    fun `a slideshow longer than the file's ceiling is cut to it, as the file will be`() {
        val set = ready(make(ticked(60)))
        assertEquals(AnimateDefaults.limits(AnimOutput.GIF).maxDurationMs.toDouble(), set.delaysMs.sum().toDouble(), 1.0)
    }

    @Test
    fun `different photos make different frames, and a fade makes in-between ones`() {
        val set = ready(make(ticked(3)))
        assertFalse(set.frames[0].argb.contentEquals(set.frames[1].argb))
        val faded = ready(make(ticked(2).withFade(true).withLoop(false)))
        assertEquals(2 + AnimateDraft.FADE_FRAMES, faded.frames.size)
        val first = faded.frames.first().argb
        val last = faded.frames.last().argb
        val mid = faded.frames[1].argb
        assertFalse(mid.contentEquals(first) || mid.contentEquals(last))
    }

    @Test
    fun `a protected photo ends it with the job's sentence before any pixel is read`() {
        val host = FakeAnimateHost().apply { protectedIds += 2L }
        val r = make(ticked(3), host)
        assertEquals(AnimateCopy.PROTECTED, (r as PreviewResult.Failed).message)
        assertTrue(host.stillCalls.isEmpty())
    }

    @Test
    fun `a photo locked while the preview is made stops it`() {
        val host = FakeAnimateHost()
        host.onStill = { asset, _ -> if (asset.id.value == 1L) host.protectedIds += 3L }
        val r = make(ticked(4), host)
        assertEquals(AnimateCopy.PROTECTED, (r as PreviewResult.Failed).message)
    }

    @Test
    fun `an unreadable photo is named by its place`() {
        val host = FakeAnimateHost().apply { unreadable += 2L }
        val r = make(ticked(3), host)
        assertEquals(AnimateCopy.cannotRead(2), (r as PreviewResult.Failed).message)
    }

    @Test
    fun `running out of memory is a sentence, not a crash`() {
        val host = FakeAnimateHost().apply { oomAboveEdge = 10 }
        assertEquals(AnimateCopy.OUT_OF_MEMORY, (make(ticked(3), host) as PreviewResult.Failed).message)
    }

    @Test
    fun `cancelling stops it and is passed on`() {
        val host = FakeAnimateHost()
        var seen: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    AnimatePreview.make(request(ticked(8)), host)
                } catch (e: CancellationException) {
                    seen = e
                    throw e
                }
            }
            host.onStill = { _, _ -> job.cancel() }
            job.join()
        }
        assertTrue("the cancellation reached the caller", seen is CancellationException)
        // It stopped at the next photo instead of reading all eight.
        assertTrue(host.stillCalls.size < 8)
    }

    // ---- a video part and a file ------------------------------------------------------------------------------------

    @Test
    fun `a video part is read through the host's reader at the preview's size`() {
        val reader = FakeVideoReader(VideoInfo(1920, 1080, 20_000))
        val host = FakeAnimateHost().apply { videos[500L] = reader }
        val d = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video)
        val set = ready(make(d, host))
        assertEquals(240, set.width)
        assertEquals(135, set.height)
        assertTrue(set.frames.size in 2..AnimatePreview.MAX_FRAMES)
        assertEquals(5_000.0, set.delaysMs.sum().toDouble(), 100.0)
        assertTrue(reader.closed)
        assertTrue(reader.maxSeen.first <= AnimatePreview.EDGE && reader.maxSeen.second <= AnimatePreview.EDGE)
    }

    @Test
    fun `an unreadable video is a sentence`() {
        val d = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video)
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, (make(d, FakeAnimateHost()) as PreviewResult.Failed).message)
    }

    @Test
    fun `a GIF is read from its file and previewed with its own timing`() {
        val seq = AnimTestKit.photoSequence(60, 40, 6, delayMs = 100, noise = 0)
        val host = FakeAnimateHost().apply { files[600L] = GifEncoder.encode(seq, GifOptions(ditherStrength = 0f)) }
        val d = AnimateDraft().withSource(AnimateSource.FILE).pickFile(gif)
        val set = ready(make(d, host))
        assertEquals(6, set.frames.size)
        assertEquals(60, set.width)
        assertEquals(600.0, set.delaysMs.sum().toDouble(), 1.0)
    }

    @Test
    fun `a file that is not moving says so`() {
        val d = AnimateDraft().withSource(AnimateSource.FILE).pickFile(gif)
        val host = FakeAnimateHost().apply { files[600L] = ByteArray(40) { 3 } }
        assertEquals(AnimateCopy.NOT_ANIMATED, (make(d, host) as PreviewResult.Failed).message)
    }

    // ---- the clock ------------------------------------------------------------------------------------------------------

    @Test
    fun `the clock shows each frame for its time, wraps round, and holds a frame with no time for a millisecond`() {
        val delays = intArrayOf(100, 200, 50)
        assertEquals(0, PreviewClock.frameAt(delays, 0))
        assertEquals(0, PreviewClock.frameAt(delays, 99))
        assertEquals(1, PreviewClock.frameAt(delays, 100))
        assertEquals(1, PreviewClock.frameAt(delays, 299))
        assertEquals(2, PreviewClock.frameAt(delays, 300))
        assertEquals(2, PreviewClock.frameAt(delays, 349))
        assertEquals(0, PreviewClock.frameAt(delays, 350))
        assertEquals(1, PreviewClock.frameAt(delays, 350 * 1000 + 150))
        assertEquals(2, PreviewClock.frameAt(delays, -1))
        assertEquals(0, PreviewClock.frameAt(intArrayOf(500), 12_345))
        assertEquals(1, PreviewClock.frameAt(intArrayOf(0, 5), 1))
        assertEquals(350L, PreviewClock.loopMs(delays))
    }

    @Test
    fun `a stalled frame advances the clock by no more than a quarter of a second`() {
        assertEquals(16L, PreviewClock.advance(0, 16_000_000L))
        assertEquals(PreviewClock.MAX_STEP_MS, PreviewClock.advance(0, 5_000_000_000L))
        assertEquals(100L, PreviewClock.advance(100, -5L))
        assertNotEquals(0L, PreviewClock.advance(0, 2_000_000L))
    }

    @Test
    fun `a preview needs a frame and every frame the same size`() {
        try {
            PreviewSet(2, 2, emptyList())
            fail()
        } catch (e: IllegalArgumentException) {
            // as it should
        }
        try {
            PreviewSet(2, 2, listOf(PreviewFrame(IntArray(3), 10)))
            fail()
        } catch (e: IllegalArgumentException) {
            // as it should
        }
    }
}
