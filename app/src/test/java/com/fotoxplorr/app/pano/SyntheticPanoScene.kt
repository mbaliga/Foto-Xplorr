package com.fotoxplorr.app.pano

import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Procedural stand-ins for photographs, for the panorama tests. There are no real photographs in the
 * repository, so the scene imitates what matters to an aligner: a 1/f spectrum (big soft shapes plus
 * progressively finer detail, as in natural images) from layered value noise, hard-edged blocks
 * (buildings, signs, windows), and a slow brightness gradient. A tile is then "photographed" from it
 * with the imperfections a handheld sweep has: its own gain and bias (auto-exposure), vignetting,
 * sensor noise, a little roll and shear, vertical jitter, a moving subject, and parallax from a
 * nearer layer. The right answer is known by construction, which a photograph never gives.
 *
 * What this does NOT prove: it has no lens distortion, no rolling shutter, no motion blur and none of
 * the structure of real scenes. Passing here is necessary, not sufficient.
 */
internal object SyntheticPanoScene {

    /** A float grey canvas, 0..255. */
    class Canvas(val width: Int, val height: Int, val grey: FloatArray) {
        /** Bilinear sample; positions outside are clamped to the border. */
        fun sample(x: Double, y: Double): Float {
            val cx = x.coerceIn(0.0, width - 1.0)
            val cy = y.coerceIn(0.0, height - 1.0)
            val x0 = floor(cx).toInt()
            val y0 = floor(cy).toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val y1 = minOf(y0 + 1, height - 1)
            val fx = (cx - x0).toFloat()
            val fy = (cy - y0).toFloat()
            val top = grey[y0 * width + x0] * (1 - fx) + grey[y0 * width + x1] * fx
            val bottom = grey[y1 * width + x0] * (1 - fx) + grey[y1 * width + x1] * fx
            return top * (1 - fy) + bottom * fy
        }
    }

    /** A nearer layer: its own picture plus a mask of where it covers the far scene. */
    class NearLayer(val canvas: Canvas, val covers: BooleanArray)

    /** A moving subject: an elliptical patch of its own brightness and texture, in the tile's own pixels. */
    class Blob(val cx: Int, val cy: Int, val rx: Int, val ry: Int, val level: Float)

    /** How one frame is taken. All defaults are a perfect camera. */
    class Shot(
        val gain: Double = 1.0,
        /** Added after gain, in grey levels. */
        val bias: Double = 0.0,
        /** Gaussian sensor noise, standard deviation in grey levels. */
        val noiseSigma: Double = 0.0,
        /** Corner brightness lost to vignetting, 0..1 (0.3 darkens the corners by 30%). */
        val vignette: Double = 0.0,
        /** Frame rotation about its centre. */
        val rollDegrees: Double = 0.0,
        /** Horizontal shear: source x moves by `shear * (y - centre)`. */
        val shear: Double = 0.0,
        val blob: Blob? = null,
        /** How far the near layer appears displaced sideways in this frame, in pixels. */
        val nearShift: Double = 0.0,
        val seed: Long = 1L,
    )

    private fun hash(ix: Int, iy: Int, seed: Int, octave: Int): Float {
        var h = ix * 73856093 xor iy * 19349663 xor seed * 83492791 xor octave * 668265263
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFFFF) / 16777216f
    }

    private fun smooth(t: Float): Float = t * t * (3 - 2 * t)

    private fun valueNoise(x: Double, y: Double, cell: Double, seed: Int, octave: Int): Float {
        val gx = x / cell
        val gy = y / cell
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        val fx = smooth((gx - ix).toFloat())
        val fy = smooth((gy - iy).toFloat())
        val a = hash(ix, iy, seed, octave)
        val b = hash(ix + 1, iy, seed, octave)
        val c = hash(ix, iy + 1, seed, octave)
        val d = hash(ix + 1, iy + 1, seed, octave)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** The far scene: 1/f noise, hard-edged blocks, a gentle left-to-right gradient. */
    fun scene(width: Int, height: Int, seed: Int, blocks: Int = 120): Canvas {
        val grey = FloatArray(width * height)
        val octaves = 7
        for (y in 0 until height) {
            for (x in 0 until width) {
                var v = 0.0
                var amplitude = 1.0
                var total = 0.0
                for (o in 0 until octaves) {
                    val cell = 192.0 / (1 shl o)
                    v += amplitude * (valueNoise(x.toDouble(), y.toDouble(), cell, seed, o) - 0.5)
                    total += amplitude
                    amplitude *= 0.5
                }
                grey[y * width + x] = (128.0 + 190.0 * v / total).toFloat()
            }
        }
        val random = Random(seed * 7919L + 13)
        repeat(blocks) {
            val w = 12 + random.nextInt(210)
            val h = 12 + random.nextInt(210)
            val x0 = random.nextInt(width)
            val y0 = random.nextInt(height)
            val level = 30f + random.nextInt(195)
            for (y in y0 until minOf(height, y0 + h)) {
                for (x in x0 until minOf(width, x0 + w)) {
                    val i = y * width + x
                    grey[i] = 0.85f * level + 0.15f * grey[i]
                }
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                grey[i] = (grey[i] * (0.88f + 0.24f * x / width)).coerceIn(15f, 240f)
            }
        }
        return Canvas(width, height, grey)
    }

    /** A near layer of tall textured posts covering roughly a fifth of the frame. */
    fun nearLayer(width: Int, height: Int, seed: Int): NearLayer {
        val canvas = scene(width, height, seed + 5000, blocks = 10)
        val covers = BooleanArray(width * height)
        val random = Random(seed * 104729L + 3)
        var covered = 0L
        while (covered < width.toLong() * height / 5) {
            val w = 60 + random.nextInt(120)
            val h = height / 2 + random.nextInt(height / 2)
            val x0 = random.nextInt(width - w)
            val y0 = height - h
            for (y in y0 until height) for (x in x0 until x0 + w) {
                val i = y * width + x
                if (!covers[i]) { covers[i] = true; covered++ }
            }
        }
        return NearLayer(canvas, covers)
    }

    /**
     * Take a [width] x [height] frame whose top-left is at ([x0], [y0]) in the scene, as [shot] describes.
     * Roll and shear turn about the frame centre, so the right offset between two frames is still
     * the difference of their top-lefts.
     */
    fun photograph(scene: Canvas, x0: Int, y0: Int, width: Int, height: Int, shot: Shot, near: NearLayer? = null): GrayImage {
        val random = Random(shot.seed)
        val out = ByteArray(width * height)
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        val angle = Math.toRadians(shot.rollDegrees)
        val c = cos(angle)
        val s = sin(angle)
        val maxR2 = cx * cx + cy * cy
        for (y in 0 until height) {
            for (x in 0 until width) {
                val lx = x - cx
                val ly = y - cy
                val sx = x0 + cx + lx * c - ly * s + shot.shear * ly
                val sy = y0 + cy + lx * s + ly * c
                var v = scene.sample(sx, sy)
                if (near != null) {
                    val nx = (sx + shot.nearShift)
                    val ix = Math.round(nx).toInt().coerceIn(0, near.canvas.width - 1)
                    val iy = Math.round(sy).toInt().coerceIn(0, near.canvas.height - 1)
                    if (near.covers[iy * near.canvas.width + ix]) v = near.canvas.sample(nx, sy)
                }
                val blob = shot.blob
                if (blob != null) {
                    val ex = (x - blob.cx).toDouble() / blob.rx
                    val ey = (y - blob.cy).toDouble() / blob.ry
                    val e = ex * ex + ey * ey
                    if (e < 1.0) {
                        val texture = (valueNoise(x.toDouble(), y.toDouble(), 9.0, shot.seed.toInt(), 3) - 0.5f) * 50f
                        val alpha = if (e < 0.8) 1f else (1f - ((e - 0.8) / 0.2).toFloat())
                        v = alpha * (blob.level + texture) + (1 - alpha) * v
                    }
                }
                val vignette = 1.0 - shot.vignette * (lx * lx + ly * ly) / maxR2
                var p = (v * shot.gain + shot.bias) * vignette
                if (shot.noiseSigma > 0) p += random.nextGaussian() * shot.noiseSigma
                out[y * width + x] = Math.round(p).toInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(width, height, out)
    }

    /** Two frames of one scene and the truth: the second's top-left minus the first's. */
    class Pair(val first: GrayImage, val second: GrayImage, val dx: Int, val dy: Int)

    const val FRAME_WIDTH = 1200
    const val FRAME_HEIGHT = 900
    private const val MARGIN = 60

    /**
     * One sweep of two [FRAME_WIDTH] x [FRAME_HEIGHT] frames overlapping by [overlap] (fraction of
     * the width). The scene is built once and reused by every [take], so many variants of the same
     * pair cost one scene each. [near] is the parallax layer, built only when asked for.
     */
    class Rig(val seed: Int, val overlap: Double) {
        val dx: Int = Math.round(FRAME_WIDTH * (1 - overlap)).toInt()
        private val width = FRAME_WIDTH + dx + 2 * MARGIN
        private val height = FRAME_HEIGHT + 2 * MARGIN
        private val scene: Canvas by lazy { scene(width, height, seed) }
        private val nearLayer: NearLayer by lazy { nearLayer(width, height, seed) }

        /** Photograph the sweep with the second frame held [dy] pixels lower; [parallax] adds the near layer. */
        fun take(dy: Int, shotA: Shot, shotB: Shot, parallax: Boolean = false): Pair {
            val near = if (parallax) nearLayer else null
            val a = photograph(scene, MARGIN, MARGIN, FRAME_WIDTH, FRAME_HEIGHT, shotA, near)
            val b = photograph(scene, MARGIN + dx, MARGIN + dy, FRAME_WIDTH, FRAME_HEIGHT, shotB, near)
            return Pair(a, b, dx, dy)
        }

        /** A frame of the far scene alone, [x] pixels from the left edge of the sweep, for single-image tests. */
        fun frame(x: Int, y: Int, shot: Shot): GrayImage = photograph(scene, MARGIN + x, MARGIN + y, FRAME_WIDTH, FRAME_HEIGHT, shot)
    }

    private val rigs = java.util.concurrent.ConcurrentHashMap<Long, Rig>()

    /** Drop the cached scenes; each test class calls this when it finishes so they do not outlive it. */
    fun releaseRigs() = rigs.clear()

    /** A shared [Rig] per (seed, overlap), so a test class does not rebuild the same scene for every variant. */
    fun rig(seed: Int, overlap: Double): Rig =
        rigs.computeIfAbsent(seed * 1000L + Math.round(overlap * 100)) { Rig(seed, overlap) }

    /** The vertical jitter each test uses for a (seed, overlap): -20..20, the design's +-20 px. */
    fun jitterFor(seed: Int, overlap: Double): Int = Random(seed * 31L + Math.round(overlap * 100)).nextInt(41) - 20

    /** A [width] x [height] window of [image] at ([x], [y]). */
    fun crop(image: GrayImage, x: Int, y: Int, width: Int, height: Int): GrayImage {
        val out = ByteArray(width * height)
        for (row in 0 until height) System.arraycopy(image.pixels, (y + row) * image.width + x, out, row * width, width)
        return GrayImage(width, height, out)
    }

    /** An image from a formula (grey levels) plus Gaussian noise of [sigma]. */
    fun pattern(width: Int, height: Int, sigma: Double, seed: Long, f: (Int, Int) -> Double): GrayImage {
        val random = Random(seed)
        return GrayImage(width, height, ByteArray(width * height) { i ->
            Math.round(f(i % width, i / width) + random.nextGaussian() * sigma).toInt().coerceIn(0, 255).toByte()
        })
    }

    /** A per-frame exposure: gain 0.8..1.2, bias +-10% of the range, 3% noise -- the design's imperfections. */
    fun handheldShot(random: Random, seed: Long, blob: Blob? = null, vignette: Double = 0.0): Shot = Shot(
        gain = 0.8 + 0.4 * random.nextDouble(),
        bias = (random.nextDouble() * 2 - 1) * 25.5,
        noiseSigma = 0.03 * 255,
        vignette = vignette,
        blob = blob,
        seed = seed,
    )
}
