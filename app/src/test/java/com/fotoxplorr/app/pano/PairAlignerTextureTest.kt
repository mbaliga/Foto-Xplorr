package com.fotoxplorr.app.pano

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * A repeating texture laid over ordinary scene content: siding, blinds, a picket or mesh fence, a
 * corrugated wall in front of a street. [PairAlignerConfidenceTest] only uses PURE patterns, where
 * the top level of the pyramid already sees many equal slides; here the top level (which averages
 * the fine period away) sees the scene and picks the right slide, and the fine levels then have to
 * not slip a whole period. The promise under test is the aligner's headline one: whatever it
 * answers, `confident = true` must be within 3 px of the truth; where it cannot tell, it must say so.
 * It is allowed to be unsure here, never confidently wrong. The same goes for the opposite texture,
 * faint smooth clouds, whose correlation peak is wide, and a control with only fine gravel-like
 * detail, which the golden scenes (all strong large-scale structure) do not cover.
 */
class PairAlignerTextureTest {

    companion object {
        private const val MARGIN = 60
        private val baseScenes = java.util.concurrent.ConcurrentHashMap<Int, SyntheticPanoScene.Canvas>()

        @JvmStatic
        @org.junit.AfterClass
        fun release() = baseScenes.clear()
    }

    private val width = SyntheticPanoScene.FRAME_WIDTH
    private val height = SyntheticPanoScene.FRAME_HEIGHT

    private enum class Family { VERTICAL_SINE, VERTICAL_STRIPES, HORIZONTAL_STRIPES, GRID }

    private fun overlay(family: Family, x: Int, y: Int, period: Int, amplitude: Double): Double = when (family) {
        Family.VERTICAL_SINE -> amplitude * sin(2 * PI * x / period)
        Family.VERTICAL_STRIPES -> if ((x % period) * 2 < period) amplitude else -amplitude
        Family.HORIZONTAL_STRIPES -> if ((y % period) * 2 < period) amplitude else -amplitude
        Family.GRID -> if ((x % period) * 2 < period) amplitude else if ((y % period) * 2 < period) -amplitude else 0.0
    }

    /** The 1/f scene at half contrast plus the repeating [family], so the content is real and the period sits on top of it. */
    private fun scene(overlap: Double, family: Family, period: Int, amplitude: Double): SyntheticPanoScene.Canvas {
        val dx = Math.round(width * (1 - overlap)).toInt()
        val w = width + dx + 2 * MARGIN
        val h = height + 2 * MARGIN
        val base = baseScenes.computeIfAbsent(Math.round(overlap * 100).toInt()) { SyntheticPanoScene.scene(w, h, 1) }
        val grey = FloatArray(w * h) { i ->
            (0.5 * base.grey[i] + 64.0 + overlay(family, i % w, i / w, period, amplitude)).toFloat().coerceIn(0f, 255f)
        }
        return SyntheticPanoScene.Canvas(w, h, grey)
    }

    private class Case(val label: String, val offset: Offset, val errX: Int, val errY: Int) {
        val error get() = max(abs(errX), abs(errY))
    }

    private fun run(family: Family, period: Int, overlap: Double, amplitude: Double): Case {
        val dx = Math.round(width * (1 - overlap)).toInt()
        val dy = SyntheticPanoScene.jitterFor(period, overlap)
        val scene = scene(overlap, family, period, amplitude)
        val random = Random(period * 31L + Math.round(overlap * 100))
        val a = SyntheticPanoScene.photograph(scene, MARGIN, MARGIN, width, height, SyntheticPanoScene.handheldShot(random, 1L))
        val b = SyntheticPanoScene.photograph(scene, MARGIN + dx, MARGIN + dy, width, height, SyntheticPanoScene.handheldShot(random, 2L))
        val offset = PairAligner.align(a, b)
        return Case("$family p=$period overlap $overlap amp $amplitude", offset, offset.dx - dx, offset.dy - dy)
    }

    @Test
    fun `a period over real content is never confidently a period out`() {
        val wrong = ArrayList<String>()
        var confidentRight = 0
        var refused = 0
        for (family in Family.values()) for (period in listOf(5, 6, 8, 10, 12, 16, 20)) for (overlap in listOf(0.25, 0.45)) {
            val c = run(family, period, overlap, 25.0)
            when {
                c.offset.confident && c.error > 3 ->
                    wrong += "${c.label}: confident about (${c.offset.dx},${c.offset.dy}), off by (${c.errX},${c.errY}), score ${c.offset.score}"
                c.offset.confident -> confidentRight++
                else -> refused++
            }
        }
        println("periodic over content: $confidentRight confident and right, $refused unsure, ${wrong.size} confidently wrong")
        assertTrue("confidently wrong:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `a coarse period over content, 30 to 60 px, is never confidently a period out`() {
        // Past the old +-28 px rescan: period 36 at 25 percent overlap (horizontal stripes and the grid) settled one period out
        // with a score of 0.84 to 0.88 and said so confidently. 36 px at the working size is about 120 px on a 4000 px photo.
        val wrong = ArrayList<String>()
        var confidentRight = 0
        var refused = 0
        for (family in listOf(Family.VERTICAL_STRIPES, Family.HORIZONTAL_STRIPES, Family.GRID)) {
            for (period in listOf(30, 36, 42, 48, 54, 60)) for (overlap in listOf(0.25, 0.45)) for (amplitude in listOf(15.0, 25.0, 40.0)) {
                val c = run(family, period, overlap, amplitude)
                when {
                    c.offset.confident && c.error > 3 ->
                        wrong += "${c.label}: confident about (${c.offset.dx},${c.offset.dy}), off by (${c.errX},${c.errY}), score ${c.offset.score}"
                    c.offset.confident -> confidentRight++
                    else -> refused++
                }
            }
        }
        println("coarse periodic over content: $confidentRight confident and right, $refused unsure, ${wrong.size} confidently wrong")
        assertTrue("confidently wrong:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("the rescan refuses nearly everything ($refused unsure of ${confidentRight + refused})", confidentRight >= refused)
    }

    @Test
    fun `a fine period over content is either found or refused, the sine and clapboard cases that slipped a period`() {
        // The families and periods the audit's port of the aligner got confidently wrong (-12, +12, -20 px, -5/+12 in dy).
        val cases = listOf(
            Triple(Family.VERTICAL_SINE, 6, 0.25), Triple(Family.VERTICAL_SINE, 10, 0.45), Triple(Family.VERTICAL_SINE, 12, 0.25),
            Triple(Family.VERTICAL_STRIPES, 6, 0.45), Triple(Family.VERTICAL_STRIPES, 12, 0.25),
            Triple(Family.HORIZONTAL_STRIPES, 5, 0.25), Triple(Family.HORIZONTAL_STRIPES, 12, 0.45),
        )
        for ((family, period, overlap) in cases) for (amplitude in listOf(15.0, 30.0)) {
            val c = run(family, period, overlap, amplitude)
            assertTrue(
                "${c.label}: confident about (${c.offset.dx},${c.offset.dy}), truth off by (${c.errX},${c.errY}), score ${c.offset.score}",
                !c.offset.confident || c.error <= 3,
            )
        }
    }

    @Test
    fun `a period over content that the aligner does find is found to the pixel`() {
        // Not everything may be refused: a gentle period over strong content must still be a trusted, exact stitch.
        val c = run(Family.VERTICAL_SINE, 40, 0.45, 12.0)
        assertTrue("${c.label}: got (${c.offset.dx},${c.offset.dy}) off by (${c.errX},${c.errY}), score ${c.offset.score}", c.error <= 2)
        assertTrue("${c.label}: a plain texture over content was refused (score ${c.offset.score})", c.offset.confident)
    }

    // ---- smooth, low-contrast content and fine detail --------------------------------------------

    /** A 1/f scene (no hard blocks) blurred wide, centred on 0 with unit spread: the clouds before any contrast is chosen. */
    private fun cloudBase(w: Int, h: Int, seed: Int): FloatArray {
        var grey = SyntheticPanoScene.scene(w, h, seed, blocks = 0).grey
        repeat(2) { grey = boxBlur(grey, w, h, 7) }
        return normalised(grey)
    }

    private fun normalised(grey: FloatArray): FloatArray {
        var mean = 0.0
        for (v in grey) mean += v
        mean /= grey.size
        var spread = 0.0
        for (v in grey) spread += (v - mean) * (v - mean)
        spread = Math.sqrt(spread / grey.size)
        return FloatArray(grey.size) { ((grey[it] - mean) / spread).toFloat() }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            var sum = 0.0
            for (x in -r..r) sum += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = (sum / (2 * r + 1)).toFloat()
                sum += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0.0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum / (2 * r + 1)).toFloat()
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private class Outcome(val right: Int, val refused: Int, val wrong: List<String>)

    /** Photograph [scene] twice at 25 percent overlap with the design's gain, bias and noise, and sort the answers. */
    private fun sweep(scenes: List<SyntheticPanoScene.Canvas>, params: PairAligner.Params, noise: List<Double>, vignette: Double): Outcome {
        val overlap = 0.25
        val dx = Math.round(width * (1 - overlap)).toInt()
        var right = 0
        var refused = 0
        val wrong = ArrayList<String>()
        for ((n, scene) in scenes.withIndex()) for (sigma in noise) {
            val dy = if (n % 2 == 0) 6 else -9
            val random = Random(n * 7L)
            val a = SyntheticPanoScene.photograph(scene, MARGIN, MARGIN, width, height, SyntheticPanoScene.Shot(gain = 0.9 + 0.2 * random.nextDouble(), bias = random.nextDouble() * 10 - 5, noiseSigma = sigma, vignette = vignette, seed = n * 2L))
            val b = SyntheticPanoScene.photograph(scene, MARGIN + dx, MARGIN + dy, width, height, SyntheticPanoScene.Shot(gain = 0.9 + 0.2 * random.nextDouble(), bias = random.nextDouble() * 10 - 5, noiseSigma = sigma, vignette = vignette, seed = n * 2L + 1))
            val o = PairAligner.align(a, b, params)
            val error = max(abs(o.dx - dx), abs(o.dy - dy))
            when {
                !o.confident -> refused++
                error > 3 -> wrong += "scene $n noise $sigma: confident about (${o.dx},${o.dy}), off by ${o.dx - dx},${o.dy - dy}, score ${o.score}"
                else -> right++
            }
        }
        return Outcome(right, refused, wrong)
    }

    @Test
    fun `faint clouds are either found to a few pixels or refused, never confidently several pixels out`() {
        val dx = Math.round(width * 0.75).toInt()
        val w = width + dx + 2 * MARGIN
        val h = height + 2 * MARGIN
        val bases = (1..4).map { cloudBase(w, h, it) }
        // Contrast 8 and 14 grey levels of spread: faint, but enough to score 0.8 and up with a peak tens of pixels wide.
        val scenes = listOf(8.0, 14.0).flatMap { contrast ->
            bases.map { base -> SyntheticPanoScene.Canvas(w, h, FloatArray(base.size) { (150.0 + base[it] * contrast).toFloat() }) }
        }
        val guarded = sweep(scenes, PairAligner.Params(), listOf(0.5, 1.5), 0.1)
        println("faint clouds: ${guarded.right} confident and right, ${guarded.refused} unsure, ${guarded.wrong.size} confidently wrong")
        assertTrue("confidently wrong:\n" + guarded.wrong.joinToString("\n"), guarded.wrong.isEmpty())

        // Teeth: with the wide-peak rule off the same pictures ARE confidently several pixels out, so the rule is what holds.
        val unguarded = sweep(scenes, PairAligner.Params(shoulderMargin = -1f), listOf(0.5, 1.5), 0.1)
        println("faint clouds without the wide-peak rule: ${unguarded.right} right, ${unguarded.refused} unsure, ${unguarded.wrong.size} confidently wrong")
        assertTrue("the wide-peak rule is not being exercised", unguarded.wrong.isNotEmpty())
    }

    @Test
    fun `fine gravel-like detail with little large-scale structure is found to the pixel`() {
        // The golden scenes have strong big shapes; this is the other end: detail a few pixels across and almost nothing large.
        for (overlap in listOf(0.25, 0.45)) {
            val dx = Math.round(width * (1 - overlap)).toInt()
            val w = width + dx + 2 * MARGIN
            val h = height + 2 * MARGIN
            for (seed in 1..2) {
                val noise = Random(seed * 99L)
                val fine = normalised(boxBlur(FloatArray(w * h) { noise.nextGaussian().toFloat() }, w, h, 1))
                val faint = SyntheticPanoScene.scene(w, h, seed, blocks = 0).grey
                val scene = SyntheticPanoScene.Canvas(w, h, FloatArray(w * h) { (128.0 + 40.0 * fine[it] + 0.15 * (faint[it] - 128f)).toFloat().coerceIn(0f, 255f) })
                val dy = if (seed == 1) -12 else 14
                val random = Random(seed * 7L)
                val a = SyntheticPanoScene.photograph(scene, MARGIN, MARGIN, width, height, SyntheticPanoScene.handheldShot(random, seed * 2L))
                val b = SyntheticPanoScene.photograph(scene, MARGIN + dx, MARGIN + dy, width, height, SyntheticPanoScene.handheldShot(random, seed * 2L + 1))
                val o = PairAligner.align(a, b)
                assertTrue("overlap $overlap seed $seed: got (${o.dx},${o.dy}), truth ($dx,$dy), score ${o.score}", abs(o.dx - dx) <= 2 && abs(o.dy - dy) <= 2)
                assertTrue("overlap $overlap seed $seed: fine detail was refused (score ${o.score})", o.confident)
            }
        }
    }
}
