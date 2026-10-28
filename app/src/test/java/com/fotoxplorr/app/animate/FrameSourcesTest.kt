package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.EditRecipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [StillsFrameSource] and [VideoFrameSource] behind fake libraries. */
class FrameSourcesTest {
    private val black = 0xFF000000.toInt()

    private fun stills(vararg ids: Long, hold: Int = 1000, transition: StillTransition? = null, recipe: EditRecipe? = null) =
        AnimateInput.Stills(ids.map { AnimStill(AnimateKit.asset(it), hold, recipe) }, transition)

    private fun slideshow(host: FakeAnimateHost, input: AnimateInput.Stills, fit: FitMode = FitMode.CONTAIN, background: Int = black, probe: Int = 480) =
        StillsFrameSource(host, input, fit, background, probe)

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun refusal(block: suspend () -> Unit): AnimSourceException {
        try {
            runBlocking { block() }
        } catch (e: AnimSourceException) {
            return e
        }
        fail("expected a refusal")
        throw IllegalStateException()
    }

    // ---- stills ---------------------------------------------------------------------------------------

    @Test
    fun `a slideshow is described by the first picture's shape and each still's hold`() {
        val h = FakeAnimateHost()
        val input = AnimateInput.Stills(
            listOf(AnimStill(AnimateKit.asset(1, "first.jpg", taken = 555), 800), AnimStill(AnimateKit.asset(2), 1500), AnimStill(AnimateKit.asset(3), 300)),
        )
        val info = run { slideshow(h, input).open() }
        assertEquals(480, info.width)
        assertEquals(360, info.height)
        assertEquals(listOf(800, 1500, 300), info.delaysMs.toList())
        assertEquals("first.jpg", info.name)
        assertEquals(555L, info.dateTakenMillis)
        assertEquals("unrelated pictures change everywhere", 1.0, info.changeFraction, 0.0)
        assertEquals(Transparency.NONE, info.transparency)
    }

    @Test
    fun `a frame is the still at the size asked for`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2))
        run { s.open() }
        val px = run { s.frame(1, 240, 180) }
        assertEquals(240 * 180, px.size)
        assertArrayEquals(AnimateKit.picture(2, 240, 180).argb, px)
    }

    @Test
    fun `the still is read at the edge the canvas needs, and once`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2, 3))
        run { s.open() }
        h.stillCalls.clear()
        run { s.frame(0, 240, 180) }
        run { s.frame(1, 240, 180) }
        run { s.frame(2, 240, 180) }
        run { s.frame(2, 240, 180) }
        assertEquals(listOf(1L, 2L, 3L), h.stillCalls.map { it.id })
        assertTrue(h.stillCalls.all { it.edge == 240 })
    }

    @Test
    fun `a still of another shape is letterboxed onto the first picture's canvas`() {
        val h = FakeAnimateHost()
        h.stillSize = { id -> if (id == 2L) 300 to 600 else 640 to 480 }
        h.render = { _, w, ht -> AnimateKit.solid(w, ht, 0xFFC80000.toInt()) }
        val s = slideshow(h, stills(1, 2), background = 0xFF0000FF.toInt())
        run { s.open() }
        val px = run { s.frame(1, 240, 180) }
        assertEquals("a tall picture in a wide canvas: bar at the left", 0xFF0000FF.toInt(), px[90 * 240 + 2])
        assertEquals("the picture in the middle", 0xFFC80000.toInt(), px[90 * 240 + 120])
        assertEquals("and a bar at the right", 0xFF0000FF.toInt(), px[90 * 240 + 238])
    }

    @Test
    fun `cover fills the canvas and reads the still at twice the edge`() {
        val h = FakeAnimateHost()
        h.stillSize = { id -> if (id == 2L) 300 to 600 else 640 to 480 }
        h.render = { _, w, ht -> AnimateKit.solid(w, ht, 0xFFC80000.toInt()) }
        val s = slideshow(h, stills(1, 2), fit = FitMode.COVER, background = 0xFF0000FF.toInt())
        run { s.open() }
        h.stillCalls.clear()
        val px = run { s.frame(1, 240, 180) }
        assertTrue(px.all { it == 0xFFC80000.toInt() })
        assertEquals(480, h.stillCalls.single().edge)
    }

    @Test
    fun `a see-through still is drawn over the background, so the animation is opaque`() {
        val h = FakeAnimateHost()
        h.render = { _, w, ht -> RawFrame(w, ht, IntArray(w * ht) { if (it % w < w / 2) 0 else 0xFF00FF00.toInt() }) }
        val s = slideshow(h, stills(1, 2), background = 0xFF112233.toInt())
        run { s.open() }
        val px = run { s.frame(0, 100, 75) }
        assertEquals(0xFF112233.toInt(), px[0])
        assertEquals(0xFF00FF00.toInt(), px[99])
        assertTrue(px.all { (it ushr 24) == 255 })
    }

    @Test
    fun `the array handed out is the caller's, and wiping it does not damage the next ask`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2))
        run { s.open() }
        val a = run { s.frame(0, 100, 75) }
        a.fill(0)
        assertArrayEquals(AnimateKit.picture(1, 100, 75).argb, run { s.frame(0, 100, 75) })
    }

    @Test
    fun `a cross-fade frame is the cross-fade of the two stills at the timeline's mix`() {
        val h = FakeAnimateHost()
        val t = StillTransition(frames = 3, frameMs = 40, easing = Easing.LINEAR)
        val s = slideshow(h, stills(1, 2, transition = t))
        val info = run { s.open() }
        assertEquals("hold, 3 in-betweens, hold", listOf(1000, 40, 40, 40, 1000), info.delaysMs.toList())
        val mid = run { s.frame(2, 120, 90) }
        val expect = CrossFade.blend(AnimateKit.picture(1, 120, 90).argb, AnimateKit.picture(2, 120, 90).argb, 0.5)
        assertArrayEquals(expect, mid)
        assertArrayEquals("the holds are the plain stills", AnimateKit.picture(2, 120, 90).argb, run { s.frame(4, 120, 90) })
    }

    @Test
    fun `with cross-fades every photo is still read once when the frames are asked for in order`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2, 3, 4, transition = StillTransition(frames = 4, loop = true)))
        val info = run { s.open() }
        h.stillCalls.clear()
        for (i in info.delaysMs.indices) run { s.frame(i, 96, 72) }
        val counts = h.stillCalls.groupBy { it.id }.mapValues { it.value.size }
        assertEquals("each photo once, though the loop fades the last back to the first", setOf(1L, 2L, 3L, 4L), counts.keys)
        assertTrue(counts.toString(), counts.values.all { it <= 2 })
    }

    @Test
    fun `a saved edit goes to the host with the still, and a crop asks for a wider read`() {
        val h = FakeAnimateHost()
        val recipe = EditRecipe(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        val s = slideshow(h, stills(1, 2, recipe = recipe))
        run { s.open() }
        val open = h.stillCalls.first()
        assertSame(recipe, open.recipe)
        assertEquals("half the picture kept: read twice as large", 960, open.edge)
        h.stillCalls.clear()
        run { s.frame(1, 240, 180) }
        assertSame(recipe, h.stillCalls.single().recipe)
        assertEquals(480, h.stillCalls.single().edge)
    }

    @Test
    fun `a still with no edit passes none`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2))
        run { s.open() }
        assertTrue(h.stillCalls.all { it.recipe == null })
    }

    @Test
    fun `a protected first photo is refused before it is read`() {
        val h = FakeAnimateHost().apply { protectedIds += 1L }
        val s = slideshow(h, stills(1, 2))
        try {
            run { s.open() }
            fail("expected a refusal")
        } catch (e: AnimSourceProtectedException) {
            assertEquals(AnimateCopy.PROTECTED, e.message)
        }
        assertTrue("nothing was decoded", h.stillCalls.isEmpty())
    }

    @Test
    fun `a photo that cannot be read is named by its place in the list`() {
        val h = FakeAnimateHost().apply { unreadable += 2L }
        val s = slideshow(h, stills(1, 2, 3))
        run { s.open() }
        assertEquals(AnimateCopy.cannotRead(2), refusal { s.frame(1, 100, 75) }.message)
        val first = FakeAnimateHost().apply { unreadable += 1L }
        assertEquals(AnimateCopy.cannotRead(1), refusal { slideshow(first, stills(1, 2)).open() }.message)
    }

    @Test
    fun `a photo locked while the job runs stops the next read`() {
        val h = FakeAnimateHost()
        val s = slideshow(h, stills(1, 2, 3))
        run { s.open() }
        run { s.frame(0, 100, 75) }
        h.protectedIds += 2L
        try {
            run { s.frame(1, 100, 75) }
            fail("expected a refusal")
        } catch (e: AnimSourceProtectedException) {
            // fine
        }
    }

    @Test
    fun `a photo that went missing as it was read is no longer available, not unreadable`() {
        val h = FakeAnimateHost()
        h.unreadable += 2L
        h.onStill = { asset, _ -> if (asset.id.value == 2L) h.protectedIds += 2L }
        val s = slideshow(h, stills(1, 2))
        run { s.open() }
        assertEquals(AnimateCopy.noLongerAvailable(2), refusal { s.frame(1, 100, 75) }.message)
    }

    @Test
    fun `no stills is a sentence`() {
        val s = slideshow(FakeAnimateHost(), AnimateInput.Stills(emptyList()))
        assertEquals(AnimateCopy.NEED_PICTURES, refusal { s.open() }.message)
    }

    @Test
    fun `an out of memory in the host is not swallowed`() {
        val h = FakeAnimateHost().apply { oomAboveEdge = 100 }
        val s = slideshow(h, stills(1, 2))
        try {
            run { s.open() }
            fail("expected an OutOfMemoryError")
        } catch (e: OutOfMemoryError) {
            // the job takes the next rung
        }
    }

    // ---- a video clip ----------------------------------------------------------------------------------

    private fun clip(start: Long = 1000, end: Long = 2000, fps: Double = 10.0) = AnimateInput.Clip(AnimateKit.video(9), start, end, fps)

    private fun clipSource(h: FakeAnimateHost, input: AnimateInput.Clip = clip()) = VideoFrameSource(h, input)

    private fun hostWith(reader: FakeVideoReader?): FakeAnimateHost = FakeAnimateHost().apply { if (reader != null) videos[9L] = reader }

    @Test
    fun `a clip is described by the video's size and its ticks`() {
        val r = FakeVideoReader(VideoInfo(1280, 720, 10_000))
        val info = run { clipSource(hostWith(r)).open() }
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals(10, info.delaysMs.size)
        assertTrue(info.delaysMs.all { it == 100 })
        assertEquals("VID_0009.mp4", info.name)
    }

    @Test
    fun `each tick reads the frame at the start of the tick, at the size asked`() {
        val r = FakeVideoReader()
        val s = clipSource(hostWith(r))
        run { s.open() }
        val px = run { s.frame(3, 320, 240) }
        assertEquals(listOf(1300L), r.times)
        assertEquals(320 to 240, r.maxSeen)
        assertEquals(320 * 240, px.size)
        assertArrayEquals(AnimateKit.picture(130, 320, 240).argb, px)
    }

    @Test
    fun `a clip asked to run past the video's end stops at the end`() {
        val r = FakeVideoReader(VideoInfo(640, 480, 1500))
        val info = run { clipSource(hostWith(r), clip(start = 1000, end = 4000)).open() }
        assertEquals("500 ms at 10 fps", 5, info.delaysMs.size)
    }

    @Test
    fun `a clip that starts after the video ends has no length`() {
        val r = FakeVideoReader(VideoInfo(640, 480, 800))
        val s = clipSource(hostWith(r), clip(start = 1000, end = 2000))
        assertEquals(AnimateCopy.CLIP_EMPTY, refusal { s.open() }.message)
        assertTrue("and the reader was given back", r.closed)
    }

    @Test
    fun `a video that does not say how long it is is trusted`() {
        val r = FakeVideoReader(VideoInfo(640, 480, 0))
        assertEquals(10, run { clipSource(hostWith(r)).open() }.delaysMs.size)
    }

    @Test
    fun `an unreadable video is one sentence, however it fails`() {
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, refusal { clipSource(hostWith(null)).open() }.message)
        val r = FakeVideoReader(info = null)
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, refusal { clipSource(hostWith(r)).open() }.message)
        assertTrue(r.closed)
        val throwing = object : VideoReader {
            override fun info(): VideoInfo? = throw IllegalStateException("native crash")
            override fun frameAt(timeMs: Long, maxWidth: Int, maxHeight: Int): RawFrame? = null
            override fun close() {}
        }
        val h = FakeAnimateHost()
        val s = VideoFrameSource(object : AnimateHost by h {
            override fun openVideo(asset: com.fotoxplorr.app.media.MediaAsset): VideoReader = throwing
        }, clip())
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, refusal { s.open() }.message)
    }

    @Test
    fun `a protected video is refused before it is opened, and again at every read`() {
        val r = FakeVideoReader()
        val h = hostWith(r).apply { protectedIds += 9L }
        try {
            run { clipSource(h).open() }
            fail("expected a refusal")
        } catch (e: AnimSourceProtectedException) {
            // fine
        }
        assertTrue(r.times.isEmpty())
        val h2 = hostWith(FakeVideoReader())
        val s = clipSource(h2)
        run { s.open() }
        run { s.frame(0, 100, 75) }
        h2.protectedIds += 9L
        try {
            run { s.frame(1, 100, 75) }
            fail("expected a refusal")
        } catch (e: AnimSourceProtectedException) {
            // fine
        }
    }

    @Test
    fun `a missing frame repeats the one before, a few times, then gives up with a sentence`() {
        val r = FakeVideoReader()
        val s = clipSource(hostWith(r), clip(start = 0, end = 2000, fps = 10.0))
        run { s.open() }
        val good = run { s.frame(0, 64, 48) }
        r.missing += listOf(100L, 200L, 300L, 400L, 500L, 600L)
        for (i in 1..5) assertArrayEquals("tick $i holds the frame before", good, run { s.frame(i, 64, 48) })
        assertEquals(AnimateCopy.VIDEO_STOPS, refusal { s.frame(6, 64, 48) }.message)
    }

    @Test
    fun `a frame that comes back after a gap resets the count`() {
        val r = FakeVideoReader()
        val s = clipSource(hostWith(r), clip(start = 0, end = 3000, fps = 10.0))
        run { s.open() }
        run { s.frame(0, 64, 48) }
        r.missing += listOf(100L, 200L, 300L, 400L, 500L, 800L, 900L, 1000L, 1100L, 1200L)
        for (i in 1..5) run { s.frame(i, 64, 48) }
        run { s.frame(6, 64, 48) }
        for (i in 8..12) run { s.frame(i, 64, 48) }
    }

    @Test
    fun `a video that gives no frame at all is unreadable, not a repeat of nothing`() {
        val r = FakeVideoReader()
        r.missing += (1000L..1900L step 100L)
        val s = clipSource(hostWith(r))
        run { s.open() }
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, refusal { s.frame(0, 64, 48) }.message)
    }

    @Test
    fun `a first tick with no frame takes the next one that has, and does not give up the clip`() {
        val r = FakeVideoReader()
        r.missing += 1000L
        val s = clipSource(hostWith(r))
        run { s.open() }
        val first = run { s.frame(0, 64, 48) }
        assertArrayEquals("the frame of the next tick stands in", AnimateKit.picture(110, 64, 48).argb, first)
        assertEquals(listOf(1000L, 1100L), r.times)
        // And the clip goes on from there with its ordinary tolerance.
        run { s.frame(1, 64, 48) }
    }

    @Test
    fun `the first frame is looked for only a few ticks ahead`() {
        val r = FakeVideoReader()
        r.missing += (1000L..1300L step 100L)
        val s = clipSource(hostWith(r))
        run { s.open() }
        assertEquals(AnimateCopy.VIDEO_UNREADABLE, refusal { s.frame(0, 64, 48) }.message)
        assertEquals(listOf(1000L, 1100L, 1200L, 1300L), r.times)
    }

    @Test
    fun `a job cancelled while the video is being opened still gives the reader back`() {
        val reader = FakeVideoReader()
        val job = kotlinx.coroutines.Job()
        val cancelling = object : VideoReader by reader {
            override fun info(): VideoInfo? {
                job.cancel() // the cancel lands while the open is on the IO thread
                return reader.info()
            }
        }
        val h = FakeAnimateHost()
        val s = VideoFrameSource(object : AnimateHost by h {
            override fun openVideo(asset: com.fotoxplorr.app.media.MediaAsset): VideoReader = cancelling
        }, clip())
        try {
            runBlocking(job) { s.open() }
            fail("expected a cancel")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // fine
        }
        s.close() // as the job's own finally does
        assertTrue("the retriever was released", reader.closed)
    }

    @Test
    fun `a reader that throws for a frame is treated as a missing frame`() {
        var calls = 0
        val reader = object : VideoReader {
            override fun info() = VideoInfo(640, 480, 5000)
            override fun frameAt(timeMs: Long, maxWidth: Int, maxHeight: Int): RawFrame? {
                if (++calls == 2) throw IllegalArgumentException("decoder hiccup")
                return AnimateKit.picture(1, maxWidth, maxHeight)
            }
            override fun close() {}
        }
        val h = FakeAnimateHost().apply { videos[9L] = FakeVideoReader() }
        val s = VideoFrameSource(object : AnimateHost by h {
            override fun openVideo(asset: com.fotoxplorr.app.media.MediaAsset): VideoReader = reader
        }, clip())
        run { s.open() }
        val a = run { s.frame(0, 64, 48) }
        val b = run { s.frame(1, 64, 48) }
        assertArrayEquals(a, b)
        assertNotSame(a, b)
    }

    @Test
    fun `a frame smaller than the plan is stretched to the plan's size`() {
        val r = FakeVideoReader(frames = { _, _, _ -> AnimateKit.solid(32, 24, 0xFF336699.toInt()) })
        val s = clipSource(hostWith(r))
        run { s.open() }
        val px = run { s.frame(0, 64, 48) }
        assertEquals(64 * 48, px.size)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun `closing gives the reader back and is safe twice`() {
        val r = FakeVideoReader()
        val s = clipSource(hostWith(r))
        run { s.open() }
        s.close()
        s.close()
        assertTrue(r.closed)
        assertFalse(r.times.isNotEmpty())
    }

    // ---- the retriever's pure choices -----------------------------------------------------------------------

    @Test
    fun `a landscape frame of a portrait video needs turning, and so does the reverse`() {
        val portrait = VideoInfo(480, 640, 1000)
        val landscape = VideoInfo(640, 480, 1000)
        assertTrue(RetrieverVideoReader.needsTurn(AnimateKit.solid(640, 480, 0), portrait))
        assertTrue(RetrieverVideoReader.needsTurn(AnimateKit.solid(480, 640, 0), landscape))
        assertFalse(RetrieverVideoReader.needsTurn(AnimateKit.solid(480, 640, 0), portrait))
        assertFalse(RetrieverVideoReader.needsTurn(AnimateKit.solid(640, 480, 0), landscape))
        assertFalse("a square cannot be told", RetrieverVideoReader.needsTurn(AnimateKit.solid(500, 500, 0), VideoInfo(500, 500, 1)))
    }

    @Test
    fun `a size is fitted inside the limits in its own shape and never enlarged`() {
        assertEquals(320 to 240, RetrieverVideoReader.fitInside(640, 480, 320, 320))
        assertEquals(240 to 320, RetrieverVideoReader.fitInside(480, 640, 320, 320))
        assertEquals(640 to 480, RetrieverVideoReader.fitInside(640, 480, 2000, 2000))
        assertEquals(1 to 1, RetrieverVideoReader.fitInside(10, 10, 1, 1))
    }
}
