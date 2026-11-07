package com.fotoxplorr.app.pano

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * How long, and how big, is a real-sized job? Eight 12 MP photos (4000 x 3000, 26 mm equivalent,
 * so f = 3004 px) drawn onto a canvas planned for 24 MP, in three arrangements of the same eight
 * photos (grep PANO-TIME in the test output). The pixels are a cheap arithmetic pattern, not a
 * picture: this measures the compositor's own work (warp, blend, write, mask, crop) and the
 * allocations, not the decoder, whose time is reported separately. Host JVM on a shared 4-core
 * machine; a phone is slower by an unknown factor (a guess is 2-4x), and the Android decoder and the
 * JPEG encoder are not in these numbers.
 */
class PanoCompositorTimingTest {

    private class Timed(val sources: List<FunctionSource>, val decodeNanos: AtomicLong)

    /** [count] photos of [w] x [h] whose decode costs one pass of arithmetic over the pixels. */
    private fun sources(count: Int, w: Int, h: Int): Timed {
        val nanos = AtomicLong(0)
        val list = List(count) { i ->
            FunctionSource(w, h, PanoGeometry.focalPixels(26, w, h)) { tw, th ->
                val t0 = System.nanoTime()
                val argb = IntArray(tw * th)
                var o = 0
                for (y in 0 until th) for (x in 0 until tw) {
                    argb[o++] = (0xFF shl 24) or (((x + i * 31) and 255) shl 16) or (((y shr 1) and 255) shl 8) or ((x xor y) and 255)
                }
                nanos.addAndGet(System.nanoTime() - t0)
                PanoPixels(tw, th, argb)
            }
        }
        return Timed(list, nanos)
    }

    private class Row(val label: String, val step: Int, val w: Int, val h: Int)

    private fun measure(row: Row, workers: Int, repeat: Int = 1): String {
        var line = ""
        repeat(repeat) {
            val timed = sources(8, row.w, row.h)
            val sizes = PanoCompositor.nativeSizes(timed.sources)
            // A little vertical wander, as a handheld sweep has: up to 40 px over the sweep.
            val positions = timed.sources.indices.map { PanoPoint(it * row.step, listOf(0, 14, 6, 31, 40, 22, 9, 27)[it]) }
            val plan = PanoGeometry.planPlaced(sizes, positions, PanoGeometry.DEFAULT_MAX_PIXELS)
            val canvas = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
            val layers = sizes.indices.sumOf { sizes[it].width.toLong() * sizes[it].height } * plan.scale * plan.scale / plan.pixels
            val t0 = System.nanoTime()
            val composed = runBlocking {
                PanoCompositor.compose(timed.sources, positions, plan, canvas, workers = workers, findCrop = false)
            }
            val drawn = System.nanoTime()
            val mask = PanoCompositor.validMask(canvas, plan.bandHeight)
            val crop = AutoCrop.cropRect(mask, canvas.width, canvas.height)
            val done = System.nanoTime()
            assertNotNull(crop)
            assertEquals(plan.canvasWidth, composed.width)
            assertTrue("within the 24 MP budget: ${plan.pixels}", plan.pixels <= PanoGeometry.DEFAULT_MAX_PIXELS)
            val decodeSize = PanoCompositor.decodeSize(timed.sources[0], plan)
            val decodeMs = timed.decodeNanos.get() / 1_000_000
            line = "%s, %d workers: canvas %dx%d = %.1f MP (%s, scale %.3f), photos decoded at %dx%d, %.1f layers per pixel; draw %d ms (of which the synthetic decode %d ms summed over the photos), mask+crop %d ms, crop %dx%d".format(
                row.label, workers, plan.canvasWidth, plan.canvasHeight, plan.pixels / 1e6, plan.limit, plan.scale,
                decodeSize.width, decodeSize.height, layers, (drawn - t0) / 1_000_000, decodeMs, (done - drawn) / 1_000_000,
                crop!!.width, crop.height,
            )
            println("PANO-TIME $line")
            // A generous guard against a pathological slowdown, not a promise: the host is shared.
            assertTrue("draw took ${(drawn - t0) / 1_000_000} ms", (drawn - t0) / 1_000_000 < 120_000)
        }
        return line
    }

    @Test
    fun `eight 12 MP photos onto a 24 MP canvas, in three arrangements`() {
        // 80% overlap: the work-per-pixel worst case, and the arrangement that fills the 24 MP budget with landscape photos.
        measure(Row("8 x 4000x3000 landscape, 80% overlap", 694, 4000, 3000), workers = PanoCompositor.defaultWorkers(), repeat = 2)
        measure(Row("8 x 4000x3000 landscape, 80% overlap", 694, 4000, 3000), workers = 1)
        // 35% overlap, a normal sweep: the 8192 px edge ceiling decides the size, not the 24 MP budget.
        measure(Row("8 x 4000x3000 landscape, 35% overlap", 2295, 4000, 3000), workers = PanoCompositor.defaultWorkers(), repeat = 2)
        // Portrait photos, the way a panorama is usually held, at 60% overlap: close to 24 MP at a believable overlap.
        measure(Row("8 x 3000x4000 portrait, 60% overlap", 1115, 3000, 4000), workers = PanoCompositor.defaultWorkers(), repeat = 2)
    }
}
