package com.fotoxplorr.app.pano

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Where the finished panorama is drawn: a [width] x [height] ARGB canvas read and written in
 * rectangles. A heap array ([IntArrayCanvas]) in the tests; on the phone a Bitmap, whose pixels
 * live in native memory, so a 24 MP canvas is not 96 MB of Java heap. Both calls must accept
 * several threads at once as long as their rectangles do not overlap, which is all the
 * compositor does. A new canvas is all zero, i.e. fully transparent.
 */
interface PanoCanvas {
    val width: Int
    val height: Int

    /** Copy the [w] x [h] rectangle at ([x], [y]) into the start of [into], row-major with a stride of [w]. */
    fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray)

    /** Copy [from] (row-major, stride [w]) onto the [w] x [h] rectangle at ([x], [y]). */
    fun write(x: Int, y: Int, w: Int, h: Int, from: IntArray)
}

/** A [PanoCanvas] on one `IntArray`, `0xAARRGGBB` per pixel, for tests and for canvases that fit the heap. */
class IntArrayCanvas(override val width: Int, override val height: Int) : PanoCanvas {
    val pixels: IntArray

    init {
        require(width > 0 && height > 0) { "a canvas must not be empty" }
        require(width.toLong() * height <= Int.MAX_VALUE) { "a canvas this size does not fit one array" }
        pixels = IntArray(width * height)
    }

    override fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray) {
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, into, row * w, w)
    }

    override fun write(x: Int, y: Int, w: Int, h: Int, from: IntArray) {
        for (row in 0 until h) System.arraycopy(from, row * w, pixels, (y + row) * width + x, w)
    }
}

/**
 * Draws the panorama: every photo is warped onto the cylinder, scaled by its exposure multiplier,
 * and blended into the canvas, a band of rows at a time. Pure Kotlin over [PanoSource] and
 * [PanoCanvas]; nothing here knows about Android, files or bitmaps.
 *
 * ## The geometry
 * The layout ([PanoLayout], then [PanoGeometry.planPlaced]) works in NATIVE cylinder pixels: each
 * photo is [PanoGeometry.warpedSize] wide and as tall as it is, and sits at its position. The
 * canvas is those pixels times the plan's scale (never above 1). For every canvas pixel the
 * compositor goes backwards: canvas pixel -> native cylinder (u, v) from the photo's centre ->
 * [PanoGeometry.inverse] (`x = f*tan(u/f)`, `y = v/cos(u/f)`) -> a point in the decoded photo,
 * read bilinearly. The column part (`x` and the row stretch `1/cos`) depends only on the column,
 * so it is tabulated once per photo; a pixel then costs one multiply to find its row. Pixel
 * centres are at half-integers throughout, so a warp at scale 1 and an integer position leaves the
 * photo's own pixels where they were.
 *
 * Photos are decoded at the canvas scale ([decodeSize]), not at their own: a 12 MP photo going
 * onto a 6 MP canvas is read as a 6 MP bitmap, which is what keeps memory about two sources plus
 * the canvas whatever the size of the sweep.
 *
 * ## The order of work
 * Photos are drawn left to right by position, not in the order given (a layout whose photos are not
 * in sweep order still comes out right). Each photo in turn is warped, a band of rows at a time,
 * into a strip as wide as the photo; where that strip overlaps what is already on the canvas the two
 * are mixed by [SeamBlend.blendBand] (the first photo of the pair being the canvas, which is on
 * the left) and the result is written back. Only two sources are alive at any moment: the one being
 * drawn and the one being decoded for next. The bands of one photo are spread over up to
 * [MAX_WORKERS] threads of the default dispatcher; they never touch the same pixels, so the answer does
 * not depend on the thread count or the band height (the tests compare them bit for bit).
 *
 * ## What it does not do
 * No parallax fix, no seam that steers around a moving person (the straight feather is
 * [SeamBlend]'s), no linear-light blending, no lens-distortion model (the cylinder maths assumes a
 * rectilinear photo; a phone's own correction makes that nearly true). It resamples with a bilinear
 * kernel, so a photo shrunk a long way by the decoder is sharper than one the warp shrinks on the
 * cylinder's edges, where up to about 1.4x minification is not filtered. Quality on real photographs is
 * unproven; the tests use procedural scenes and a synthetic cylinder.
 *
 * A [OutOfMemoryError] from a decode or a buffer is not caught here: the job catches it, takes the
 * next rung of [PanoGeometry.ladder] and starts again on a NEW canvas (a partly drawn one is not
 * reusable).
 */
object PanoCompositor {

    /** The most threads one photo's bands are spread over. */
    const val MAX_WORKERS = 4

    /** Threads to use when the caller does not say: the cores, up to [MAX_WORKERS]. */
    fun defaultWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_WORKERS)

    /** How far (in canvas pixels) a sample may fall outside a photo and still count as the photo's edge. */
    private const val EDGE_SLACK = 0.5

    /** Bilinear weights and gains are fixed point: 8 bits for weights, 12 for the gain. */
    private const val GAIN_ONE = 4096
    private const val GAIN_LIMIT = 16 * GAIN_ONE
    private const val OPAQUE = 0xFF shl 24
    private const val MAX_ANGLE = PI / 2 - 1e-6

    /** The share of the progress bar the drawing takes; the rest is finding the crop. */
    private const val DRAWING_SHARE = 0.95f

    /**
     * What a drawn canvas holds. [crop] is the largest rectangle with no empty pixel in it
     * ([AutoCrop.cropRect] on the canvas's own alpha), or null when [findCrop] was off or nothing was drawn.
     */
    class Composed(val width: Int, val height: Int, val crop: AutoCrop.CropRect?)

    /** Every source's size on the cylinder at its own resolution: what [PanoGeometry.planPlaced] wants. */
    fun nativeSizes(sources: List<PanoSource>): List<PanoSize> =
        sources.map { PanoGeometry.warpedSize(it.width, it.height, it.focal) }

    /** The size to decode [source] at for a canvas made from [plan]: its full size times the plan's scale, at least 1 x 1. */
    fun decodeSize(source: PanoSource, plan: PanoGeometry.Plan): PanoSize {
        val sx = plan.canvasWidth.toDouble() / plan.fullWidth
        val sy = plan.canvasHeight.toDouble() / plan.fullHeight
        return PanoSize(max(1, (source.width * sx).roundToInt()), max(1, (source.height * sy).roundToInt()))
    }

    /** One photo laid on the canvas: where, how big, and the strip of canvas it covers. */
    private class Placed(
        val index: Int,
        val source: PanoSource,
        /** Left and top of the photo's rectangle on the native cylinder canvas (minimum 0), and its warped width. */
        val left: Double,
        val top: Double,
        val warpedWidth: Int,
        val gain12: Int,
        /** The canvas columns [x0, x1) and rows [y0, y1) the photo covers. */
        val x0: Int,
        val x1: Int,
        val y0: Int,
        val y1: Int,
    ) {
        val stripWidth: Int get() = x1 - x0
    }

    /** The per-column half of the inverse warp for one photo: which source pixel pair and weight, and the row stretch. */
    private class Columns(val ix0: IntArray, val wx: IntArray, val vScale: DoubleArray)

    /** Two reusable band buffers; reallocated only when the band's size changes (the photo's last band is short). */
    private class Scratch {
        var first = IntArray(0)
        var second = IntArray(0)
        private var spareFirst = IntArray(0)
        private var spareSecond = IntArray(0)

        fun fit(size: Int) {
            if (first.size == size) return
            // The full-size pair and the short last band's pair swap places instead of being rebuilt.
            val f = first
            val s = second
            first = if (spareFirst.size == size) spareFirst else IntArray(size)
            second = if (spareSecond.size == size) spareSecond else IntArray(size)
            spareFirst = f
            spareSecond = s
        }
    }

    /** What the drawing shares across threads. */
    private class Run(
        val canvas: PanoCanvas,
        val sx: Double,
        val sy: Double,
        val bandHeight: Int,
        val feather: Int,
        val scratch: Array<Scratch>,
        val shouldStop: () -> Boolean,
        val onProgress: (Float) -> Unit,
        val totalBands: Int,
    ) {
        val stopped = AtomicBoolean(false)
        private val lock = Any()
        private var done = 0

        fun bandDone() = synchronized(lock) {
            done++
            onProgress(DRAWING_SHARE * done / totalBands)
        }
    }

    /**
     * Draw the panorama of [sources] (the photos of one joined run, as [PanoLayout] named them) onto
     * [canvas], a NEW canvas of exactly the plan's size.
     *
     * @param positions each photo's top-left on the native cylinder canvas, one per source, in the
     *   unit of [nativeSizes] ([PanoLayout.Layout.scaledPositions]); shifted here so the smallest x
     *   and y are 0.
     * @param plan [PanoGeometry.planPlaced] of [nativeSizes] and [positions]: it must describe exactly
     *   these photos (checked) and may be any rung of the ladder.
     * @param gains the multiplier for each photo's three channels ([ExposureGain.Result.gains]); null is all 1.
     * @param feather the width of the cosine ramp at each seam, in canvas columns ([SeamBlend.DEFAULT_FEATHER]).
     * @param workers threads for one photo's bands; the answer is the same for any number.
     * @param findCrop also find the largest hole-free rectangle (costs a 1-byte-per-pixel mask).
     * @param shouldStop asked from several threads, between bands; when it turns true the call ends
     *   with a [CancellationException]. Cancelling the calling coroutine does the same.
     * @param onProgress 0..1, rising, called under a lock from the drawing threads, one call per band.
     *   Must be quick and must not call back into the compositor.
     * @throws PanoSourceUnavailableException when a source cannot be decoded.
     */
    suspend fun compose(
        sources: List<PanoSource>,
        positions: List<PanoPoint>,
        plan: PanoGeometry.Plan,
        canvas: PanoCanvas,
        gains: List<Double>? = null,
        feather: Int = SeamBlend.DEFAULT_FEATHER,
        workers: Int = defaultWorkers(),
        findCrop: Boolean = true,
        shouldStop: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Composed {
        val n = sources.size
        require(n in 1..PanoGeometry.MAX_PHOTOS) { "a sweep is 1 to ${PanoGeometry.MAX_PHOTOS} photos, got $n" }
        require(positions.size == n) { "one position per photo" }
        require(gains == null || gains.size == n) { "one gain per photo" }
        require(workers >= 1) { "at least one worker" }
        require(feather >= 0) { "the feather must not be negative" }
        require(canvas.width == plan.canvasWidth && canvas.height == plan.canvasHeight) {
            "the canvas must be ${plan.canvasWidth}x${plan.canvasHeight}"
        }

        val sizes = nativeSizes(sources)
        var minX = Long.MAX_VALUE
        var minY = Long.MAX_VALUE
        var maxX = Long.MIN_VALUE
        var maxY = Long.MIN_VALUE
        for (i in 0 until n) {
            minX = min(minX, positions[i].x.toLong()); minY = min(minY, positions[i].y.toLong())
            maxX = max(maxX, positions[i].x.toLong() + sizes[i].width); maxY = max(maxY, positions[i].y.toLong() + sizes[i].height)
        }
        require(maxX - minX == plan.fullWidth && maxY - minY == plan.fullHeight) { "the plan was made for other photos" }

        val sx = plan.canvasWidth.toDouble() / plan.fullWidth
        val sy = plan.canvasHeight.toDouble() / plan.fullHeight
        val placed = List(n) { i ->
            val left = (positions[i].x - minX).toDouble()
            val top = (positions[i].y - minY).toDouble()
            val x0 = Math.round(left * sx).toInt().coerceIn(0, canvas.width)
            val x1 = Math.round((left + sizes[i].width) * sx).toInt().coerceIn(x0, canvas.width)
            val y0 = Math.round(top * sy).toInt().coerceIn(0, canvas.height)
            val y1 = Math.round((top + sizes[i].height) * sy).toInt().coerceIn(y0, canvas.height)
            val gain = gains?.get(i) ?: 1.0
            require(gain.isFinite() && gain > 0.0) { "a gain must be a positive number" }
            Placed(i, sources[i], left, top, sizes[i].width, Math.round(gain * GAIN_ONE).toInt().coerceIn(1, GAIN_LIMIT), x0, x1, y0, y1)
        }
        // Left to right by position, ties in the given order; a photo with no footprint cannot be seen.
        val order = placed.filter { it.x1 > it.x0 && it.y1 > it.y0 }.sortedWith(compareBy({ it.left }, { it.index }))

        val bandHeight = max(1, plan.bandHeight)
        fun bandsOf(p: Placed) = (p.y1 - p.y0 + bandHeight - 1) / bandHeight
        val run = Run(
            canvas, sx, sy, bandHeight, feather,
            Array(workers) { Scratch() }, shouldStop, onProgress,
            max(1, order.sumOf { bandsOf(it) }),
        )

        onProgress(0f)
        coroutineScope {
            var incoming: Deferred<PanoPixels?>? = order.firstOrNull()?.let { decodeAsync(it, plan) }
            for ((position, photo) in order.withIndex()) {
                var current: PanoPixels? = incoming!!.await() ?: throw PanoSourceUnavailableException(photo.index)
                // The next photo starts decoding as soon as the previous one has been let go: two alive, never three.
                incoming = order.getOrNull(position + 1)?.let { decodeAsync(it, plan) }
                val previousRight = order.subList(0, position).maxOfOrNull { it.x1 } ?: Int.MIN_VALUE
                draw(run, photo, current!!, previousRight, workers, bandsOf(photo))
                current = null
                if (run.stopped.get()) throw CancellationException("panorama cancelled")
            }
        }

        var crop: AutoCrop.CropRect? = null
        if (findCrop && order.isNotEmpty()) {
            crop = withContext(Dispatchers.Default) {
                val mask = validMask(canvas, bandHeight) { shouldStop() }
                AutoCrop.cropRect(mask, canvas.width, canvas.height, shouldStop = shouldStop)
            }
        }
        onProgress(1f)
        return Composed(canvas.width, canvas.height, crop)
    }

    /** Which pixels of [canvas] have data (alpha not 0), row-major, read a [bandHeight] rows at a time. */
    fun validMask(canvas: PanoCanvas, bandHeight: Int = 64, shouldStop: () -> Boolean = { false }): BooleanArray {
        require(bandHeight >= 1) { "a band is at least one row" }
        require(canvas.width.toLong() * canvas.height <= Int.MAX_VALUE) { "a canvas this size has no mask" }
        val mask = BooleanArray(canvas.width * canvas.height)
        val band = IntArray(canvas.width * min(bandHeight, canvas.height))
        var y = 0
        while (y < canvas.height) {
            if (shouldStop()) throw CancellationException("panorama cancelled")
            val rows = min(bandHeight, canvas.height - y)
            canvas.read(0, y, canvas.width, rows, band)
            val base = y * canvas.width
            for (i in 0 until rows * canvas.width) mask[base + i] = band[i] ushr 24 != 0
            y += rows
        }
        return mask
    }

    private fun CoroutineScope.decodeAsync(photo: Placed, plan: PanoGeometry.Plan): Deferred<PanoPixels?> =
        async(Dispatchers.IO) {
            val size = decodeSize(photo.source, plan)
            photo.source.decode(size.width, size.height)
        }

    /** Draw one photo: its bands go to up to [workers] threads, each taking the next undone band. */
    private suspend fun draw(run: Run, photo: Placed, pixels: PanoPixels, previousRight: Int, workers: Int, bands: Int) {
        val columns = columnsFor(run, photo, pixels)
        val next = AtomicInteger(0)
        coroutineScope {
            for (w in 0 until min(workers, bands)) {
                launch(Dispatchers.Default) {
                    val scratch = run.scratch[w]
                    while (true) {
                        ensureActive()
                        if (run.stopped.get()) return@launch
                        if (run.shouldStop()) {
                            run.stopped.set(true)
                            return@launch
                        }
                        val band = next.getAndIncrement()
                        if (band >= bands) return@launch
                        val top = photo.y0 + band * run.bandHeight
                        val rows = min(run.bandHeight, photo.y1 - top)
                        drawBand(run, photo, pixels, columns, previousRight, scratch, top, rows)
                        run.bandDone()
                    }
                }
            }
        }
    }

    /** Warp [rows] rows of [photo] from canvas row [top], blend with what is already there, write back. */
    private fun drawBand(
        run: Run,
        photo: Placed,
        pixels: PanoPixels,
        columns: Columns,
        previousRight: Int,
        scratch: Scratch,
        top: Int,
        rows: Int,
    ) {
        val stripWidth = photo.stripWidth
        scratch.fit(stripWidth * rows)
        warp(run, photo, pixels, columns, top, rows, scratch.second)
        // Anything already drawn in these columns reaches at most previousRight, so beyond it the photo stands alone.
        val overlapEnd = min(previousRight, photo.x1) - photo.x0
        if (overlapEnd > 0) {
            run.canvas.read(photo.x0, top, stripWidth, rows, scratch.first)
            SeamBlend.blendBand(scratch.first, scratch.second, stripWidth, rows, 0, overlapEnd, run.feather, scratch.first)
            run.canvas.write(photo.x0, top, stripWidth, rows, scratch.first)
        } else {
            run.canvas.write(photo.x0, top, stripWidth, rows, scratch.second)
        }
    }

    /**
     * The per-column table of the inverse warp, for the pixels actually decoded. Canvas column X has
     * its centre at native cylinder `n = (X + 0.5) / sx`; relative to the photo's centre that is
     * `u`, the plane column is `f * tan(u/f)`, and the decoded photo is the full-size photo times
     * `decodedWidth / width`.
     */
    private fun columnsFor(run: Run, photo: Placed, pixels: PanoPixels): Columns {
        val f = photo.source.focal
        val dw = pixels.width
        val rx = dw.toDouble() / photo.source.width
        val ry = pixels.height.toDouble() / photo.source.height
        val centre = photo.left + photo.warpedWidth / 2.0
        val strip = photo.stripWidth
        val ix0 = IntArray(strip)
        val wx = IntArray(strip)
        val vScale = DoubleArray(strip)
        for (c in 0 until strip) {
            val u = (photo.x0 + c + 0.5) / run.sx - centre
            val theta = u / f
            if (abs(theta) >= MAX_ANGLE) {
                ix0[c] = -1
                continue
            }
            val sourceX = (f * tan(theta) + photo.source.width / 2.0) * rx - 0.5
            if (sourceX < -0.5 - EDGE_SLACK || sourceX > dw - 0.5 + EDGE_SLACK) {
                ix0[c] = -1
                continue
            }
            // Position in 1/256 pixel, rounded: a sample that lands within 1/512 of a pixel centre IS that pixel.
            val q = (sourceX.coerceIn(0.0, dw - 1.0) * 256.0 + 0.5).toInt()
            ix0[c] = q shr 8
            wx[c] = q and 255
            vScale[c] = ry / cos(theta)
        }
        return Columns(ix0, wx, vScale)
    }

    /** Fill [out] (strip width x [rows]) with the photo warped onto canvas rows [top] until `top + rows`; alpha 0 where it has no data. */
    private fun warp(run: Run, photo: Placed, pixels: PanoPixels, columns: Columns, top: Int, rows: Int, out: IntArray) {
        val strip = photo.stripWidth
        val dw = pixels.width
        val dh = pixels.height
        val src = pixels.argb
        val half = dh / 2.0 + EDGE_SLACK
        val rowOffset = dh / 2.0 - 0.5
        val centreY = photo.top + photo.source.height / 2.0
        val gain12 = photo.gain12
        var o = 0
        for (r in 0 until rows) {
            val v = (top + r + 0.5) / run.sy - centreY
            for (c in 0 until strip) {
                val ix = columns.ix0[c]
                val d = v * columns.vScale[c]
                if (ix < 0 || d > half || d < -half) {
                    out[o++] = 0
                    continue
                }
                var sourceY = d + rowOffset
                if (sourceY < 0.0) sourceY = 0.0 else if (sourceY > dh - 1.0) sourceY = dh - 1.0
                val qy = (sourceY * 256.0 + 0.5).toInt()
                val iy = qy shr 8
                val wy = qy and 255
                val wxx = columns.wx[c]
                val ix1 = if (ix + 1 < dw) ix + 1 else ix
                val row0 = iy * dw
                val row1 = (if (iy + 1 < dh) iy + 1 else iy) * dw
                // Four weights that add to exactly 256.
                val w11 = (wxx * wy) shr 8
                val w10 = wxx - w11
                val w01 = wy - w11
                val w00 = 256 - w10 - w01 - w11
                val p00 = src[row0 + ix]
                val p10 = src[row0 + ix1]
                val p01 = src[row1 + ix]
                val p11 = src[row1 + ix1]
                // Red and blue share one int (a 16-bit lane each), green has its own: 255 * 256 never overflows a lane.
                val rb = (p00 and 0xFF00FF) * w00 + (p10 and 0xFF00FF) * w10 + (p01 and 0xFF00FF) * w01 + (p11 and 0xFF00FF) * w11 + 0x800080
                val g = (p00 and 0xFF00) * w00 + (p10 and 0xFF00) * w10 + (p01 and 0xFF00) * w01 + (p11 and 0xFF00) * w11 + 0x8000
                var colour = ((rb ushr 8) and 0xFF00FF) or ((g ushr 8) and 0xFF00)
                if (gain12 != GAIN_ONE) colour = gained(colour, gain12)
                out[o++] = OPAQUE or colour
            }
        }
    }

    private fun gained(colour: Int, gain12: Int): Int {
        val r = min(255, (((colour ushr 16) and 0xFF) * gain12 + GAIN_ONE / 2) shr 12)
        val g = min(255, (((colour ushr 8) and 0xFF) * gain12 + GAIN_ONE / 2) shr 12)
        val b = min(255, ((colour and 0xFF) * gain12 + GAIN_ONE / 2) shr 12)
        return (r shl 16) or (g shl 8) or b
    }
}
