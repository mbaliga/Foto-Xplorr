package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Does the compositor put a scene back together? Two kinds of evidence, both procedural because the
 * repository holds no photographs:
 *
 * 1. PLANAR round trip: tiles cut from a flat colour scene (with the camera's exposure and a
 *    vertical jitter) are drawn at their true positions on a focal length so long that the
 *    cylinder warp does nothing; the canvas is compared with the scene cut at the same place. This
 *    checks placement, gains, blending and the crop, and with the real aligner, layout and exposure
 *    solver in front of it, the whole chain.
 * 2. REAL GEOMETRY: a ground-truth CYLINDRICAL panorama is photographed through a pinhole camera at
 *    known yaw steps (the plane-to-cylinder maths run the other way, in the test), and stitched back
 *    with the correct focal length. This is what verifies the focal-length and warp maths: with a
 *    wrong focal length the same test fails by a wide margin (the controls below).
 *
 * Printed lines (grep PANO-COMPOSE in the test output) carry the measured errors; the asserts have
 * margin over them. Procedural scenes have no lens distortion, rolling shutter, motion blur or
 * parallax, so this is necessary, not sufficient.
 */
class PanoCompositorRoundTripTest {

    private val planarF = 1.0e7

    private fun report(label: String, e: Error) = println("PANO-COMPOSE $label: $e")

    // ---- 1. planar -------------------------------------------------------------------------

    private class Planar(val scene: ColourScene, val xs: List<Int>, val ys: List<Int>, val exposures: List<Double>, val tileW: Int, val tileH: Int) {
        fun positions() = xs.indices.map { PanoPoint(xs[it], ys[it]) }
        /** Gains that undo the exposures, mean 1: the output is scene * constant, constant = 1 / mean(1/e). */
        fun exactGains(): List<Double> {
            val mean = exposures.map { 1 / it }.average()
            return exposures.map { (1 / it) / mean }
        }
        val constant: Double get() = 1 / exposures.map { 1 / it }.average()
    }

    private fun planar(noise: Double = 0.0): Pair<Planar, List<FunctionSource>> {
        val scene = ColourScene.of(2200, 560, 3)
        val rig = Planar(
            scene,
            xs = listOf(40, 395, 750, 1105, 1460),
            ys = listOf(30, 42, 21, 47, 35),
            exposures = listOf(1.0, 1.12, 0.92, 1.08, 0.97),
            tileW = 640, tileH = 480,
        )
        val sources = rig.xs.indices.map { i ->
            FunctionSource(rig.tileW, rig.tileH, planarF) { tw, th ->
                assertEquals("decoded at the scale of the canvas (1.0)", rig.tileW, tw)
                assertEquals(rig.tileH, th)
                scene.tile(rig.xs[i], rig.ys[i], tw, th, rig.exposures[i], noise, seed = 100L + i)
            }
        }
        return rig to sources
    }

    @Test
    fun `tiles of a planar scene come back within one grey level, with no hole in the crop`() {
        val (rig, sources) = planar()
        val result = stitch(sources, rig.positions(), gains = rig.exactGains())
        val plan = result.plan
        assertEquals("a small sweep is drawn at full size", 1.0, plan.scale, 0.0)
        val crop = result.composed.crop
        assertNotNull(crop)
        crop!!
        // The rows every tile has: tile tops are 21..47, so 26 rows are lost to the jitter.
        assertEquals("the crop spans the whole width (the photos' sides are straight)", result.canvas.width, crop.width)
        assertEquals("the crop is the rows all five tiles have", 480 - (47 - 21), crop.height)
        val minX = rig.xs.min()
        val minY = rig.ys.min()
        val error = compareToTruth(result.canvas, crop) { x, y, into -> rig.scene.expected(x + minX, y + minY, rig.constant, into) }
        report("planar 5 tiles, exposures 0.92-1.12, jitter 26 px", error)
        assertTrue("mean error $error", error.mean <= 0.6)
        assertTrue("worst error $error", error.max <= 3.0)
    }

    @Test
    fun `without the exposure gains the seams show, with them they do not`() {
        val (rig, sources) = planar()
        val without = stitch(sources, rig.positions())
        val with = stitch(sources, rig.positions(), gains = rig.exactGains())
        val minX = rig.xs.min()
        val minY = rig.ys.min()
        // A fair yardstick for "without": the same constant, so only the per-photo steps differ.
        val e0 = compareToTruth(without.canvas, without.composed.crop!!) { x, y, into -> rig.scene.expected(x + minX, y + minY, rig.constant, into) }
        val e1 = compareToTruth(with.canvas, with.composed.crop!!) { x, y, into -> rig.scene.expected(x + minX, y + minY, rig.constant, into) }
        report("planar without gains", e0)
        report("planar with gains", e1)
        assertTrue("gains must matter: without $e0, with $e1", e0.mean > 5 * e1.mean)
    }

    @Test
    fun `the whole chain from tiles to canvas (aligner, layout, exposure, compositor) lands within a few grey levels of the scene`() {
        // 1.5 grey levels of sensor noise, the camera's exposure and 26 px of vertical jitter.
        val (rig, sources) = planar(noise = 1.5)
        val tiles = sources.map { it.decode(rig.tileW, rig.tileH)!! }
        val greys = tiles.map { it.toGray() }
        val offsets = (0 until greys.size - 1).map { PairAligner.align(greys[it], greys[it + 1]) }
        assertTrue("every join is trusted: ${offsets.map { it.score }}", offsets.all { it.confident })
        val layout = PanoLayout.layout(greys.map { PanoSize(it.width, it.height) }, offsets)
        assertTrue(layout.complete && layout.joinable)
        val exposure = ExposureGain.compute(greys, layout.positions)
        println("PANO-COMPOSE planar chain exposure: worst leftover step %.4f, clamped %s".format(exposure.worstMismatch, exposure.clamped))

        val result = stitch(sources, layout.scaledPositions(1.0), gains = exposure.gains)
        val crop = result.composed.crop!!
        // Truth: the alignment found the true offsets, so canvas = scene cut at the same place, times the same constant.
        val minX = rig.xs.min()
        val minY = rig.ys.min()
        val error = compareToTruth(result.canvas, crop) { x, y, into -> rig.scene.expected(x + minX, y + minY, rig.constant, into) }
        report("planar whole chain, noise 1.5, exposures 0.92-1.12", error)
        assertTrue("offsets are the true ones: ${offsets.map { it.dx to it.dy }}", offsets.indices.all { offsets[it].dx == rig.xs[it + 1] - rig.xs[it] && offsets[it].dy == rig.ys[it + 1] - rig.ys[it] })
        assertTrue("mean error $error", error.mean <= 2.0)
        assertTrue("crop is the shared rows ${crop.height}", crop.height >= 480 - 26 - 2)
    }

    // ---- 2. real geometry ------------------------------------------------------------------

    /** Five photos 640 x 480 of a cylinder, f = 520 px (about a 65 degree view), the camera yawing by a fixed arc each time. */
    private class Cylinder(val f: Double, val tileW: Int = 640, val tileH: Int = 480, val step: Int = 400, val count: Int = 5) {
        val warpedW: Int = PanoGeometry.warpedSize(tileW, tileH, f).width
        /** Left of each photo's rectangle on the cylinder canvas (native pixels) and its top: a little vertical drift. */
        val lefts: List<Int> = List(count) { it * step }
        val tops: List<Int> = List(count) { listOf(6, 0, 10, 2, 8)[it % 5] }
        val fullW: Int = lefts.last() + warpedW
        val fullH: Int = 480 + 10
        fun positions() = lefts.indices.map { PanoPoint(lefts[it], tops[it]) }
    }

    /** Photos of [scene] (a cylinder at [scale] times native size) as the pinhole camera takes them at the size asked for. */
    private fun cylinderSources(cyl: Cylinder, scene: ColourScene, scale: Double, exposures: List<Double>, focalForSources: Double = cyl.f): List<FunctionSource> =
        cyl.lefts.indices.map { i ->
            FunctionSource(cyl.tileW, cyl.tileH, focalForSources) { tw, th ->
                // The camera's real focal length, in the pixels of the photo it was asked to produce.
                val fPixels = cyl.f * tw / cyl.tileW
                val centreU = (cyl.lefts[i] + cyl.warpedW / 2.0) * scale
                val centreV = (cyl.tops[i] + cyl.tileH / 2.0) * scale
                scene.pinhole(tw, th, fPixels, centreU, centreV, exposures[i])
            }
        }

    private fun cylinderError(cyl: Cylinder, scale: Double, scene: ColourScene, focalForSources: Double, exposures: List<Double>, gains: List<Double>?): Triple<Error, Stitched, Double> {
        val sources = cylinderSources(cyl, scene, scale, exposures, focalForSources)
        val maxPixels = (cyl.fullW.toLong() * cyl.fullH * scale * scale).toLong()
        val result = stitch(sources, cyl.positions(), maxPixels = maxPixels, gains = gains)
        val crop = result.composed.crop!!
        val constant = if (gains == null) 1.0 else 1 / exposures.map { 1 / it }.average()
        val error = compareToTruth(result.canvas, crop) { x, y, into -> scene.expected(x, y, constant, into) }
        return Triple(error, result, constant)
    }

    @Test
    fun `a cylinder photographed through a pinhole stitches back with the right focal length`() {
        val cyl = Cylinder(f = 520.0)
        val scene = ColourScene.of(cyl.fullW, cyl.fullH, 5)
        val exposures = listOf(1.0, 1.0, 1.0, 1.0, 1.0)
        val (error, result, _) = cylinderError(cyl, 1.0, scene, cyl.f, exposures, null)
        report("cylinder f=520, correct focal length, 5 photos, 30% overlap", error)
        assertEquals("drawn at full size", 1.0, result.plan.scale, 0.0)
        // The cylinder's top and bottom edges curve in toward each photo's sides, so the crop loses rows there.
        val crop = result.composed.crop!!
        assertTrue("the crop keeps most of the picture: $crop of ${result.canvas.width}x${result.canvas.height}",
            crop.height >= 400 && crop.width >= 0.95 * result.canvas.width)
        assertTrue("mean error $error", error.mean <= 1.2)
    }

    @Test
    fun `a wrong focal length is clearly worse, so the test does measure the maths`() {
        val cyl = Cylinder(f = 520.0)
        val scene = ColourScene.of(cyl.fullW, cyl.fullH, 5)
        val exposures = listOf(1.0, 1.0, 1.0, 1.0, 1.0)
        // The ghosting from a wrong warp sits at the seams, where each photo is used 150-250 px from its centre.
        val seams = (0 until cyl.count - 1).map { (cyl.lefts[it + 1] + cyl.lefts[it] + cyl.warpedW) / 2 }
        val nearSeam = { x: Int -> seams.any { abs(x - it) <= 24 } }
        val rightResult = cylinderError(cyl, 1.0, scene, cyl.f, exposures, null).second
        val right = compareToTruth(rightResult.canvas, rightResult.composed.crop!!) { x, y, into -> scene.expected(x, y, 1.0, into) }
        val rightSeam = compareToTruth(rightResult.canvas, rightResult.composed.crop!!, nearSeam) { x, y, into -> scene.expected(x, y, 1.0, into) }
        report("cylinder f=520 right focal length, whole crop", right)
        report("cylinder f=520 right focal length, seams only", rightSeam)
        // The camera is the same; the stitcher is told a focal length 12% too long or short (and 25%).
        // Photos are centred where the real camera put them, so only the warp is off.
        for (factor in listOf(1.12, 0.88, 1.25, 0.8)) {
            val wrongCyl = Cylinder(f = cyl.f * factor)
            val sources = cyl.lefts.indices.map { i ->
                FunctionSource(cyl.tileW, cyl.tileH, cyl.f * factor) { tw, th ->
                    val fPixels = cyl.f * tw / cyl.tileW
                    scene.pinhole(tw, th, fPixels, cyl.lefts[i] + cyl.warpedW / 2.0, cyl.tops[i] + cyl.tileH / 2.0)
                }
            }
            val positions = cyl.lefts.indices.map {
                PanoPoint((cyl.lefts[it] + cyl.warpedW / 2.0 - wrongCyl.warpedW / 2.0).toInt(), cyl.tops[it])
            }
            val result = stitch(sources, positions)
            val crop = result.composed.crop!!
            val minX = positions.minOf { it.x }
            val truth = { x: Int, y: Int, into: DoubleArray -> scene.expected((x + minX).coerceIn(0, scene.width - 1), y, 1.0, into) }
            val whole = compareToTruth(result.canvas, crop, expected = truth)
            val atSeams = compareToTruth(result.canvas, crop, { x -> nearSeam(x + minX) }, truth)
            report("cylinder f=520 stitched with f x$factor, whole crop", whole)
            report("cylinder f=520 stitched with f x$factor, seams only", atSeams)
            assertTrue("a $factor focal length must cost over 1.5x the right one ($right): $whole", whole.mean > 1.5 * right.mean)
            assertTrue("and over 2x at the seams ($rightSeam): $atSeams", atSeams.mean > 2.0 * rightSeam.mean)
        }
    }

    @Test
    fun `the cylinder stitches back at half scale, decoding the photos at half size`() {
        val cyl = Cylinder(f = 520.0, step = 400)
        // Even dimensions, so that half is exact.
        val fullW = cyl.fullW + (cyl.fullW % 2)
        val scene = ColourScene.of(fullW, cyl.fullH, 5)
        val half = scene.half()
        val sources = cyl.lefts.indices.map { i ->
            FunctionSource(cyl.tileW, cyl.tileH, cyl.f) { tw, th ->
                // A camera asked for half the pixels sees the half-size scene with half the focal length.
                val fPixels = cyl.f * tw / cyl.tileW
                half.pinhole(tw, th, fPixels, (cyl.lefts[i] + cyl.warpedW / 2.0) / 2, (cyl.tops[i] + cyl.tileH / 2.0) / 2)
            }
        }
        // Native extent must be even for an exact half: widen the last photo's position by one if needed.
        val positions = cyl.positions()
        val sizes = PanoCompositor.nativeSizes(sources)
        val spanW = positions.indices.maxOf { positions[it].x + sizes[it].width }
        val spanH = positions.indices.maxOf { positions[it].y + sizes[it].height }
        assertTrue("test setup: even extent", spanW % 2 == 0 && spanH % 2 == 0)
        val result = stitch(sources, positions, maxPixels = spanW.toLong() * spanH / 4)
        assertEquals(spanW / 2, result.plan.canvasWidth)
        assertEquals(spanH / 2, result.plan.canvasHeight)
        for (s in sources) {
            assertTrue("every photo decoded at 320x240, got ${s.asked}", s.asked.all { it.width == 320 && it.height == 240 })
        }
        val error = compareToTruth(result.canvas, result.composed.crop!!) { x, y, into -> half.expected(x, y, 1.0, into) }
        report("cylinder f=520 at scale 0.5", error)
        assertTrue("mean error $error", error.mean <= 2.0)
    }

    @Test
    fun `exposure gains work on the cylinder too`() {
        val cyl = Cylinder(f = 520.0)
        val scene = ColourScene.of(cyl.fullW, cyl.fullH, 5)
        val exposures = listOf(1.0, 1.1, 0.9, 1.05, 0.95)
        val mean = exposures.map { 1 / it }.average()
        val gains = exposures.map { (1 / it) / mean }
        val (error, _, _) = cylinderError(cyl, 1.0, scene, cyl.f, exposures, gains)
        report("cylinder f=520 with exposures 0.9-1.1 and gains", error)
        assertTrue("mean error $error", error.mean <= 1.5)
    }

    // ---- 3. away from the seams the photo's own pixels survive ----------------------------------

    @Test
    fun `where only one photo reaches, its pixels come through bit for bit`() {
        val (rig, sources) = planar()
        val result = stitch(sources, rig.positions(), gains = List(5) { 1.0 })
        val minX = rig.xs.min()
        val minY = rig.ys.min()
        var checked = 0
        // The first tile alone: columns left of the second tile. The last tile alone: right of the fourth's end.
        for (index in listOf(0, 4)) {
            val tile = sources[index].decode(rig.tileW, rig.tileH)!!
            val columns = if (index == 0) 0 until (rig.xs[1] - minX) else (rig.xs[3] + rig.tileW - minX) until (rig.xs[4] + rig.tileW - minX)
            for (x in columns) for (y in rig.ys[index] - minY until rig.ys[index] - minY + rig.tileH) {
                val got = result.canvas.pixels[y * result.canvas.width + x]
                val want = tile.argb[(y - (rig.ys[index] - minY)) * rig.tileW + (x - (rig.xs[index] - minX))] or (0xFF shl 24)
                assertEquals("tile $index at canvas ($x, $y)", want, got)
                checked++
            }
        }
        assertTrue("checked $checked pixels", checked > 100_000)
    }
}
