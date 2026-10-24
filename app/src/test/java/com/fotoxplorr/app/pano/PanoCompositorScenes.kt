package com.fotoxplorr.app.pano

import java.util.Random
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Colour ground truth for the compositor tests, built on [SyntheticPanoScene]'s 1/f scenes: three
 * channels that share most of their structure (so the colour image has the luminance edges of a
 * photograph) and differ enough that a swapped channel shows. Everything here is a float canvas,
 * so a "photograph" of it and a comparison against it do not add quantisation of their own.
 */
internal class ColourScene(val red: SyntheticPanoScene.Canvas, val green: SyntheticPanoScene.Canvas, val blue: SyntheticPanoScene.Canvas) {
    val width: Int get() = green.width
    val height: Int get() = green.height

    /** The ground truth at ([x], [y]), channels 0..255 as floats, bilinear. */
    fun sample(x: Double, y: Double, into: FloatArray) {
        into[0] = red.sample(x, y)
        into[1] = green.sample(x, y)
        into[2] = blue.sample(x, y)
    }

    /** The scene at half the size, each pixel the mean of 2 x 2: the ground truth for a canvas drawn at scale 0.5. */
    fun half(): ColourScene = ColourScene(half(red), half(green), half(blue))

    private fun half(c: SyntheticPanoScene.Canvas): SyntheticPanoScene.Canvas {
        val w = c.width / 2
        val h = c.height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = (c.grey[2 * y * c.width + 2 * x] + c.grey[2 * y * c.width + 2 * x + 1] +
                c.grey[(2 * y + 1) * c.width + 2 * x] + c.grey[(2 * y + 1) * c.width + 2 * x + 1]) / 4f
        }
        return SyntheticPanoScene.Canvas(w, h, out)
    }

    /** The scene's own pixels at integer ([x], [y]) as ARGB, scaled by [factor]; the expected value of a drawn canvas pixel. */
    fun expected(x: Int, y: Int, factor: Double, into: DoubleArray) {
        val i = y * width + x
        into[0] = red.grey[i] * factor
        into[1] = green.grey[i] * factor
        into[2] = blue.grey[i] * factor
    }

    /**
     * A flat tile cut from the scene: [w] x [h] pixels at ([x0], [y0]), each channel times [exposure]
     * (the camera's auto-exposure), plus Gaussian sensor noise of [noise] grey levels, rounded to 8 bits.
     */
    fun tile(x0: Int, y0: Int, w: Int, h: Int, exposure: Double = 1.0, noise: Double = 0.0, seed: Long = 1L): PanoPixels {
        val random = Random(seed)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y0 + y) * width + x0 + x
            out[y * w + x] = pack(
                red.grey[i] * exposure + if (noise > 0) random.nextGaussian() * noise else 0.0,
                green.grey[i] * exposure + if (noise > 0) random.nextGaussian() * noise else 0.0,
                blue.grey[i] * exposure + if (noise > 0) random.nextGaussian() * noise else 0.0,
            )
        }
        return PanoPixels(w, h, out)
    }

    /**
     * What a pinhole camera of focal length [focal] (in pixels of the photo it takes) sees when it looks at
     * this scene taken as a CYLINDER: the scene's column is the cylinder's arc length and its row the
     * height on a cylinder of radius [focal]. The photo is [w] x [h]; its principal point looks at
     * cylinder point ([centreU], [centreV]), in scene pixels. Each photo pixel (x, y) from the principal
     * point sees `u = centreU + focal*atan(x/focal)`, `v = centreV + focal*y/hypot(x, focal)`, the
     * forward map of [PanoGeometry], so a perfect stitch gives the scene back.
     */
    fun pinhole(w: Int, h: Int, focal: Double, centreU: Double, centreV: Double, exposure: Double = 1.0): PanoPixels {
        val out = IntArray(w * h)
        val rgb = FloatArray(3)
        for (r in 0 until h) {
            val y = r + 0.5 - h / 2.0
            for (c in 0 until w) {
                val x = c + 0.5 - w / 2.0
                val u = centreU + focal * atan(x / focal)
                val v = centreV + focal * y / hypot(x, focal)
                // Scene pixel centres are at +0.5, canvas sampling takes pixel indices.
                sample(u - 0.5, v - 0.5, rgb)
                out[r * w + c] = pack(rgb[0] * exposure, rgb[1] * exposure, rgb[2] * exposure)
            }
        }
        return PanoPixels(w, h, out)
    }

    companion object {
        /** A scene [width] x [height]; [seed] gives different pictures. */
        fun of(width: Int, height: Int, seed: Int): ColourScene {
            val luminance = SyntheticPanoScene.scene(width, height, seed)
            val a = SyntheticPanoScene.scene(width, height, seed + 1000, blocks = 40)
            val b = SyntheticPanoScene.scene(width, height, seed + 2000, blocks = 40)
            fun mix(l: SyntheticPanoScene.Canvas, c: SyntheticPanoScene.Canvas) =
                SyntheticPanoScene.Canvas(width, height, FloatArray(width * height) { 0.75f * l.grey[it] + 0.25f * c.grey[it] })
            return ColourScene(mix(luminance, a), luminance, mix(luminance, b))
        }

        fun pack(r: Double, g: Double, b: Double): Int =
            (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

        private fun clamp(v: Double): Int = Math.round(v).toInt().coerceIn(0, 255)
    }
}

/** A [PanoSource] whose pixels come from a function of the size asked for; counts and records its calls. */
internal class FunctionSource(
    override val width: Int,
    override val height: Int,
    override val focal: Double,
    private val render: (Int, Int) -> PanoPixels?,
) : PanoSource {
    val decodes = java.util.concurrent.atomic.AtomicInteger(0)
    val asked = java.util.Collections.synchronizedList(ArrayList<PanoSize>())

    override fun decode(targetWidth: Int, targetHeight: Int): PanoPixels? {
        decodes.incrementAndGet()
        asked += PanoSize(targetWidth, targetHeight)
        return render(targetWidth, targetHeight)
    }
}

/** The grey (green channel) of coloured pixels, for the aligner and the exposure solver. */
internal fun PanoPixels.toGray(): GrayImage =
    GrayImage(width, height, ByteArray(width * height) { ((argb[it] ushr 8) and 0xFF).toByte() })

/** The error of a drawn canvas against a truth: mean and largest absolute channel difference. */
internal class Error(val mean: Double, val max: Double, val pixels: Long) {
    override fun toString() = "mean %.2f max %.1f over %d px".format(mean, max, pixels)
}

/**
 * Compare [canvas] with [expected] (called for a canvas pixel, filling red, green and blue) over
 * [rect], also asserting every pixel there is opaque: a hole inside the crop is a failure.
 */
internal fun compareToTruth(
    canvas: IntArrayCanvas,
    rect: AutoCrop.CropRect,
    columns: (Int) -> Boolean = { true },
    expected: (Int, Int, DoubleArray) -> Unit,
): Error {
    val truth = DoubleArray(3)
    var sum = 0.0
    var worst = 0.0
    var counted = 0L
    for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
        if (!columns(x)) continue
        counted++
        val p = canvas.pixels[y * canvas.width + x]
        check(p ushr 24 == 0xFF) { "hole at ($x, $y) inside the crop" }
        expected(x, y, truth)
        for (ch in 0 until 3) {
            val got = (p ushr (16 - 8 * ch)) and 0xFF
            val e = kotlin.math.abs(got - truth[ch])
            sum += e
            if (e > worst) worst = e
        }
    }
    return Error(sum / (3.0 * counted), worst, counted)
}

/** A drawn panorama and the plan it was drawn on. */
internal class Stitched(val plan: PanoGeometry.Plan, val canvas: IntArrayCanvas, val composed: PanoCompositor.Composed)

/**
 * Plan [sources] at [positions] on a budget of [maxPixels] and draw them on a new canvas, the way the
 * job will: sizes from [PanoCompositor.nativeSizes], the plan from [PanoGeometry.planPlaced].
 * [bandHeight] overrides the plan's, to test that the band size does not matter.
 */
internal fun stitch(
    sources: List<PanoSource>,
    positions: List<PanoPoint>,
    maxPixels: Long = PanoGeometry.DEFAULT_MAX_PIXELS,
    gains: List<Double>? = null,
    workers: Int = PanoCompositor.defaultWorkers(),
    bandHeight: Int? = null,
    feather: Int = SeamBlend.DEFAULT_FEATHER,
    shouldStop: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {},
): Stitched {
    val base = PanoGeometry.planPlaced(PanoCompositor.nativeSizes(sources), positions, maxPixels)
    val plan = if (bandHeight == null) base else PanoGeometry.Plan(
        base.fullWidth, base.fullHeight, base.canvasWidth, base.canvasHeight, base.scale, bandHeight, base.limit,
    )
    val canvas = IntArrayCanvas(plan.canvasWidth, plan.canvasHeight)
    val composed = kotlinx.coroutines.runBlocking {
        PanoCompositor.compose(sources, positions, plan, canvas, gains, feather, workers, true, shouldStop, onProgress)
    }
    return Stitched(plan, canvas, composed)
}
