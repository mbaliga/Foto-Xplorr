package com.fotoxplorr.app.pano

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * What the compositor promises besides getting the picture right (that is [PanoCompositorRoundTripTest]):
 * the answer does not depend on how the work is cut up or threaded, photos are drawn by position,
 * gains and sizes are honoured, progress rises to 1, stopping stops, only two photos are alive at a
 * time, and bad input is refused instead of drawn.
 */
class PanoCompositorMechanicsTest {

    private val planarF = 1.0e7

    /** Four 400 x 300 tiles of a colour scene with a jitter and exposures; small enough to draw in a blink. */
    private class Rig {
        val scene = ColourScene.of(1400, 360, 11)
        val xs = listOf(10, 240, 470, 700)
        val ys = listOf(20, 31, 14, 38)
        val exposures = listOf(1.0, 1.1, 0.93, 1.05)
        val positions get() = xs.indices.map { PanoPoint(xs[it], ys[it]) }
        val gains: List<Double>
            get() {
                val mean = exposures.map { 1 / it }.average()
                return exposures.map { (1 / it) / mean }
            }
        val sources: List<FunctionSource>
            get() = xs.indices.map { i -> FunctionSource(400, 300, 1.0e7) { tw, th -> scene.tile(xs[i], ys[i], tw, th, exposures[i], 1.0, 50L + i) } }
    }

    private fun assertSameCanvas(label: String, a: Stitched, b: Stitched) {
        assertEquals("$label: width", a.canvas.width, b.canvas.width)
        assertEquals("$label: height", a.canvas.height, b.canvas.height)
        assertArrayEquals("$label: pixels", a.canvas.pixels, b.canvas.pixels)
        assertEquals("$label: crop", a.composed.crop, b.composed.crop)
    }

    // ---- banded equals unbanded, threads do not matter ----------------------------------------

    @Test
    fun `the band height and the thread count change not one pixel`() {
        val rig = Rig()
        // One band per photo (the band is taller than the canvas) is the unbanded drawing.
        val unbanded = stitch(rig.sources, rig.positions, gains = rig.gains, workers = 1, bandHeight = 100_000)
        assertEquals("a band covers the photo", 1, (unbanded.plan.canvasHeight + 99_999) / 100_000)
        for ((band, workers) in listOf(1 to 1, 1 to 4, 7 to 3, 64 to 4, 299 to 2, 300 to 4, null to 4, null to 1)) {
            val banded = stitch(rig.sources, rig.positions, gains = rig.gains, workers = workers, bandHeight = band)
            assertSameCanvas("band $band, $workers workers", unbanded, banded)
        }
        assertNotNull(unbanded.composed.crop)
    }

    @Test
    fun `at a reduced scale too the answer does not depend on the band`() {
        val rig = Rig()
        val budget = 900L * 300 // well under the native 1400 x ~340 canvas
        val a = stitch(rig.sources, rig.positions, maxPixels = budget, gains = rig.gains, workers = 1, bandHeight = 100_000)
        val b = stitch(rig.sources, rig.positions, maxPixels = budget, gains = rig.gains, workers = 4, bandHeight = 5)
        assertTrue("a smaller canvas than the photos make: scale ${a.plan.scale}", a.plan.scale < 0.9)
        assertSameCanvas("scale ${a.plan.scale}", a, b)
    }

    @Test
    fun `photos are drawn by position, not in the order given`() {
        val rig = Rig()
        val inOrder = stitch(rig.sources, rig.positions, gains = rig.gains)
        for (order in listOf(listOf(3, 2, 1, 0), listOf(2, 0, 3, 1))) {
            val sources = rig.sources
            val shuffled = stitch(order.map { sources[it] }, order.map { rig.positions[it] }, gains = order.map { rig.gains[it] })
            assertSameCanvas("order $order", inOrder, shuffled)
        }
    }

    // ---- gains, sizes -------------------------------------------------------------------------

    @Test
    fun `a gain multiplies every channel, rounds and stops at white`() {
        val colour = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        for ((gain, expected) in listOf(
            2.0 to intArrayOf(255, 200, 100),
            0.5 to intArrayOf(100, 50, 25),
            1.0 to intArrayOf(200, 100, 50),
            1.1 to intArrayOf(220, 110, 55),
            0.333 to intArrayOf(67, 33, 17),
        )) {
            val source = FunctionSource(64, 48, planarF) { w, h -> PanoPixels(w, h, IntArray(w * h) { colour }) }
            val result = stitch(listOf(source), listOf(PanoPoint(0, 0)), gains = listOf(gain))
            val p = result.canvas.pixels[10 * 64 + 20]
            assertEquals("gain $gain alpha", 0xFF, p ushr 24)
            assertEquals("gain $gain red", expected[0], (p ushr 16) and 0xFF)
            assertEquals("gain $gain green", expected[1], (p ushr 8) and 0xFF)
            assertEquals("gain $gain blue", expected[2], p and 0xFF)
            assertEquals("the whole canvas is the photo", 64 * 48L, result.composed.crop!!.area)
        }
    }

    @Test
    fun `a decoder that lands off the requested size is sampled at the right place`() {
        // A linear ramp is reproduced exactly by bilinear sampling, so any error is a misplacement.
        fun ramp(w: Int, h: Int, xScale: Double, yScale: Double) = PanoPixels(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = Math.round((x + 0.5) * xScale).toInt()
            val g = Math.round((y + 0.5) * yScale).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or 7
        })
        // The photo is 800 x 600; the canvas is half that, so 400 x 300 is asked for ...
        val full = 800
        val asked = ArrayList<PanoSize>()
        for ((returnedW, returnedH) in listOf(400 to 300, 800 to 600, 600 to 450)) {
            val source = FunctionSource(full, 600, planarF) { w, h ->
                asked += PanoSize(w, h)
                // ... and whatever comes back is the same picture: value = position in the photo / 4 (red), / 3 (green).
                ramp(returnedW, returnedH, 0.25 * full / returnedW, 0.3333333 * 600 / returnedH)
            }
            val result = stitch(listOf(source), listOf(PanoPoint(0, 0)), maxPixels = 400L * 300)
            assertEquals(400, result.canvas.width)
            assertEquals(300, result.canvas.height)
            var worst = 0.0
            for (y in 0 until 300) for (x in 0 until 400) {
                val p = result.canvas.pixels[y * 400 + x]
                // Canvas pixel centre (x + 0.5) is photo position 2 * (x + 0.5).
                worst = maxOf(worst, abs(((p ushr 16) and 0xFF) - 0.25 * 2 * (x + 0.5)))
                worst = maxOf(worst, abs(((p ushr 8) and 0xFF) - 0.3333333 * 2 * (y + 0.5)))
            }
            assertTrue("decoder returning ${returnedW}x$returnedH: worst error $worst", worst <= 1.0)
        }
        assertTrue("always asked for the canvas scale: $asked", asked.all { it.width == 400 && it.height == 300 })
    }

    @Test
    fun `decode size is the full size times the canvas scale`() {
        val a = FunctionSource(4000, 3000, 3004.0) { _, _ -> null }
        val sources = List(3) { a }
        val positions = listOf(PanoPoint(0, 0), PanoPoint(2300, 10), PanoPoint(4600, 0))
        val plan = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(sources), positions, 24_000_000L)
        val size = PanoCompositor.decodeSize(a, plan)
        assertEquals(Math.round(4000 * plan.scale).toInt(), size.width)
        assertEquals(Math.round(3000 * plan.scale).toInt(), size.height)
        assertTrue("never enlarged", size.width <= 4000 && size.height <= 3000)
        val tiny = PanoGeometry.planPlaced(listOf(PanoSize(10000, 10)), listOf(PanoPoint(0, 0)), 1000L)
        val thin = PanoCompositor.decodeSize(FunctionSource(4000, 3, 100.0) { _, _ -> null }, tiny)
        assertTrue("at least 1 x 1: $thin", thin.width >= 1 && thin.height >= 1)
    }

    // ---- shapes of sweep ------------------------------------------------------------------------

    @Test
    fun `a single photo is drawn as it is`() {
        val rig = Rig()
        val source = rig.sources[0]
        val result = stitch(listOf(source), listOf(PanoPoint(5, 9)))
        assertEquals(400, result.canvas.width)
        assertEquals(300, result.canvas.height)
        val photo = source.decode(400, 300)!!
        assertArrayEquals(IntArray(400 * 300) { photo.argb[it] or (0xFF shl 24) }, result.canvas.pixels)
        assertEquals(AutoCrop.CropRect(0, 0, 400, 300), result.composed.crop)
    }

    @Test
    fun `photos that do not touch leave a gap with no data, and the crop avoids it`() {
        val rig = Rig()
        val sources = listOf(rig.sources[0], rig.sources[3])
        val result = stitch(sources, listOf(PanoPoint(0, 0), PanoPoint(500, 0)))
        assertEquals(900, result.canvas.width)
        val gap = result.canvas.pixels[100 * 900 + 450]
        assertEquals("the gap is transparent", 0, gap ushr 24)
        val crop = result.composed.crop!!
        assertTrue("the crop is one photo's side of the gap: $crop", crop.right <= 400 || crop.left >= 500)
        assertEquals(400 * 300L, crop.area)
    }

    @Test
    fun `a photo inside another's columns and one hanging below both draw without trouble`() {
        val rig = Rig()
        val narrow = FunctionSource(150, 300, planarF) { w, h -> rig.scene.tile(100, 20, w, h, 1.0) }
        val sources = listOf(rig.sources[0], narrow, rig.sources[2])
        val positions = listOf(PanoPoint(0, 0), PanoPoint(100, 15), PanoPoint(300, 40))
        val result = stitch(sources, positions)
        assertEquals(700, result.canvas.width)
        assertEquals(340, result.canvas.height)
        val mask = PanoCompositor.validMask(result.canvas)
        val crop = result.composed.crop!!
        assertTrue("the crop has no hole", AutoCrop.allValid(mask, result.canvas.width, crop))
        assertTrue("and keeps the rows all three share: $crop", crop.height >= 260)
    }

    // ---- progress, stopping, memory -------------------------------------------------------------

    @Test
    fun `progress starts at 0, never goes back and ends at 1, once per band`() {
        val rig = Rig()
        val seen = Collections.synchronizedList(ArrayList<Float>())
        val result = stitch(rig.sources, rig.positions, bandHeight = 50, onProgress = { seen += it })
        assertEquals(0f, seen.first(), 0f)
        assertEquals(1f, seen.last(), 0f)
        for (i in 1 until seen.size) assertTrue("progress went back at $i: ${seen[i - 1]} -> ${seen[i]}", seen[i] >= seen[i - 1])
        assertTrue("all within 0..1", seen.all { it in 0f..1f })
        // 4 photos of 300 rows in bands of 50: 6 bands each; plus the 0 at the start and the 1 at the end.
        assertEquals(4 * 6 + 2, seen.size)
        assertTrue("drawing is not the whole bar (the crop is the rest): ${seen[seen.size - 2]}", seen[seen.size - 2] < 1f)
        assertNotNull(result.composed.crop)
    }

    @Test
    fun `stopping between bands ends with a cancellation and starts no more work`() {
        val rig = Rig()
        val sources = rig.sources
        val calls = AtomicInteger(0)
        val stopAfter = 5
        var thrown: Throwable? = null
        val started = System.nanoTime()
        try {
            stitch(sources, rig.positions, bandHeight = 20, workers = 2, shouldStop = { calls.incrementAndGet() > stopAfter })
        } catch (e: CancellationException) {
            thrown = e
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertNotNull("a stop is a CancellationException", thrown)
        // Two workers may each be mid-band when the flag turns: a handful of asks, not the whole job (4 photos x 15 bands).
        assertTrue("asked ${calls.get()} times", calls.get() in (stopAfter + 1)..(stopAfter + 8))
        assertTrue("the photos after the stop were never decoded: ${sources.map { it.decodes.get() }}", sources.last().decodes.get() == 0)
        assertTrue("took $seconds s", seconds < 5)
    }

    @Test
    fun `stopping while the crop is being found also stops`() {
        val rig = Rig()
        val sources = rig.sources
        val positions = rig.positions
        val plan = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(sources), positions)
        val inner = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
        // The drawing reads strips narrower than the canvas; only the mask for the crop reads whole rows.
        var findingCrop = false
        val canvas = object : PanoCanvas by inner {
            override fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray) {
                if (w == inner.width) findingCrop = true
                inner.read(x, y, w, h, into)
            }
        }
        var thrown: Throwable? = null
        try {
            runBlocking { PanoCompositor.compose(sources, positions, plan, canvas, shouldStop = { findingCrop }) }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue("the drawing finished and the crop began", findingCrop)
        assertNotNull("a stop during the crop still cancels", thrown)
    }

    @Test
    fun `cancelling the calling coroutine stops the job`() {
        val rig = Rig()
        val slow = rig.xs.indices.map { i ->
            FunctionSource(400, 300, planarF) { w, h ->
                Thread.sleep(150)
                rig.scene.tile(rig.xs[i], rig.ys[i], w, h)
            }
        }
        val positions = rig.positions
        val plan = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(slow), positions)
        val canvas = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
        var cancelled = false
        runBlocking {
            val job = launch(Dispatchers.Default) { PanoCompositor.compose(slow, positions, plan, canvas) }
            delay(80)
            job.cancelAndJoin()
            cancelled = job.isCancelled
        }
        assertTrue(cancelled)
        assertTrue("later photos were not decoded: ${slow.map { it.decodes.get() }}", slow.last().decodes.get() == 0)
    }

    @Test
    fun `each worker holds two band buffers, allocated once, whatever the photo widths and the short last band`() {
        val rig = Rig()
        // Photos of three different widths and a band height that leaves a short last band on every photo:
        // the shapes that used to make each worker keep a second, spare pair of buffers.
        val widths = listOf(400, 380, 360)
        val sources = widths.mapIndexed { i, w ->
            FunctionSource(w, 300, 1.0e7) { tw, th -> rig.scene.tile(rig.xs[i], rig.ys[i], tw, th, 1.0, 1.0, 50L + i) }
        }
        val positions = widths.indices.map { PanoPoint(rig.xs[it], rig.ys[it]) }
        val workers = 2
        val plan = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(sources), positions)
        val seen = java.util.IdentityHashMap<IntArray, Boolean>()
        val inner = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
        val canvas = object : PanoCanvas by inner {
            override fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray) {
                // Whole-row reads are the crop's mask pass, which has its own buffer.
                if (w != inner.width) synchronized(seen) { seen[into] = true }
                inner.read(x, y, w, h, into)
            }

            override fun write(x: Int, y: Int, w: Int, h: Int, from: IntArray) {
                synchronized(seen) { seen[from] = true }
                inner.write(x, y, w, h, from)
            }
        }
        val bandHeight = 7
        assertTrue("the last band is short", plan.canvasHeight % bandHeight != 0)
        val tall = PanoGeometry.Plan(plan.fullWidth, plan.fullHeight, plan.canvasWidth, plan.canvasHeight, plan.scale, bandHeight, plan.limit)
        runBlocking { PanoCompositor.compose(sources, positions, tall, canvas, workers = workers) }
        val arrays = synchronized(seen) { seen.keys.toList() }
        assertTrue("${arrays.size} band buffers for $workers workers: ${arrays.map { it.size }}", arrays.size <= 2 * workers)
        assertEquals("one size, the widest strip times the band", 1, arrays.map { it.size }.distinct().size)
        assertTrue("no bigger than the widest photo's band", arrays.all { it.size <= plan.canvasWidth * bandHeight })
        val bytes = arrays.sumOf { it.size * 4L }
        assertTrue("the plan's estimate covers what was allocated: $bytes of ${PanoCompositor.scratchBytes(sources, tall, workers)}", bytes <= PanoCompositor.scratchBytes(sources, tall, workers))
        // And the picture is the one a single-threaded, unbanded draw makes.
        val reference = stitch(sources, positions, workers = 1, bandHeight = 100_000)
        assertArrayEquals(reference.canvas.pixels, inner.pixels)
    }

    @Test
    fun `only two photos are alive at a time`() {
        val rig = Rig()
        val refs = Collections.synchronizedMap(HashMap<Int, WeakReference<IntArray>>())
        val leaks = Collections.synchronizedList(ArrayList<Int>())
        val sources = rig.xs.indices.map { i ->
            FunctionSource(400, 300, planarF) { w, h ->
                if (i >= 2) {
                    // By the time photo i is decoded, photo i-2 has been drawn and must have been let go.
                    val old = refs[i - 2]!!
                    var attempts = 0
                    while (old.get() != null && attempts++ < 40) {
                        System.gc()
                        try { ByteArray(1 shl 20) } catch (_: OutOfMemoryError) { /* nudge the collector */ }
                        Thread.sleep(10)
                    }
                    if (old.get() != null) leaks += i - 2
                }
                val pixels = rig.scene.tile(rig.xs[i], rig.ys[i], w, h)
                refs[i] = WeakReference(pixels.argb)
                pixels
            }
        }
        val result = stitch(sources, rig.positions)
        assertNotNull(result.composed.crop)
        assertTrue("photos still referenced when a later one was decoded: $leaks", leaks.isEmpty())
        assertEquals(listOf(1, 1, 1, 1), sources.map { it.decodes.get() })
    }

    // ---- refusals ---------------------------------------------------------------------------------

    @Test
    fun `a photo that cannot be read stops the job with its index, and later photos are never decoded`() {
        val rig = Rig()
        val sources = rig.sources.toMutableList()
        sources[2] = FunctionSource(400, 300, planarF) { _, _ -> null }
        try {
            stitch(sources, rig.positions)
            fail("a missing photo must not give a panorama with a hole in it")
        } catch (e: PanoSourceUnavailableException) {
            assertEquals(2, e.index)
        }
        assertEquals(0, sources[3].decodes.get())
    }

    @Test
    fun `input that does not fit is refused before any pixel is drawn`() {
        val rig = Rig()
        val sources = rig.sources
        val positions = rig.positions
        val plan = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(sources), positions)
        val canvas = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
        fun refused(label: String, block: suspend () -> Unit) {
            try {
                runBlocking { block() }
                fail("$label should have been refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        refused("canvas of another size") { PanoCompositor.compose(sources, positions, plan, IntArrayCanvas(plan.canvasWidth + 1, plan.canvasHeight)) }
        refused("plan of other photos") { PanoCompositor.compose(sources, positions.map { PanoPoint(it.x * 2, it.y) }, plan, canvas) }
        refused("one position short") { PanoCompositor.compose(sources, positions.dropLast(1), plan, canvas) }
        refused("one gain short") { PanoCompositor.compose(sources, positions, plan, canvas, gains = listOf(1.0)) }
        refused("a zero gain") { PanoCompositor.compose(sources, positions, plan, canvas, gains = listOf(1.0, 0.0, 1.0, 1.0)) }
        refused("a NaN gain") { PanoCompositor.compose(sources, positions, plan, canvas, gains = listOf(1.0, Double.NaN, 1.0, 1.0)) }
        refused("no workers") { PanoCompositor.compose(sources, positions, plan, canvas, workers = 0) }
        refused("no photos") { PanoCompositor.compose(emptyList(), emptyList(), plan, canvas) }
        assertTrue("nothing was drawn", canvas.pixels.all { it == 0 })
        assertEquals("and nothing was decoded", 0, sources.sumOf { it.decodes.get() })
        assertFalse(canvas.pixels.any { it != 0 })
    }

    @Test
    fun `decoded pixels must be the size they say`() {
        try {
            PanoPixels(4, 4, IntArray(15))
            fail("a short array is refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
