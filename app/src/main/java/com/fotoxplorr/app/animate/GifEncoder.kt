package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/** Where the palette of each GIF frame comes from. */
enum class PaletteMode {
    /** One palette for the file, built from the whole animation. Smallest; worst when the colours change. */
    GLOBAL,

    /** A palette per frame, built from the pixels that frame paints. Best colour; 3 bytes a colour more per frame. */
    LOCAL,

    /** The file's palette, except for a frame it fits badly and that is big enough to be worth a palette of its own. */
    AUTO,
}

/**
 * Knobs of the GIF writer. The defaults are for photographs and video frames.
 *
 * @param ditherStrength 0 is plain nearest colour (flat bands, smallest files); 1 is the classic
 *   Floyd-Steinberg filter (smooth gradients, noisier, larger).
 * @param maxColors colours per palette, 2 to 255: the 256th place is kept for transparency.
 * @param deltaTolerance the biggest per-channel difference (0 to 255) between a pixel and what is
 *   already on screen that still counts as "unchanged". 0 is exact; 4 to 8 helps noisy video a lot.
 * @param deltaFrames false writes every frame whole, the way a naive encoder does. Larger, and
 *   there to compare against or to serve a viewer that mishandles partial frames.
 */
class GifOptions(
    val paletteMode: PaletteMode = PaletteMode.AUTO,
    val ditherStrength: Float = 0.75f,
    val maxColors: Int = 255,
    val deltaTolerance: Int = 0,
    val deltaFrames: Boolean = true,
) {
    init {
        require(maxColors in 2..255) { "maxColors is 2 to 255" }
        require(ditherStrength in 0f..1f) { "ditherStrength is 0 to 1" }
        require(deltaTolerance in 0..255) { "deltaTolerance is 0 to 255" }
    }
}

/**
 * Writes a GIF89a one frame at a time, so a long animation never has to be held whole.
 *
 * ## What it does to keep files small
 * - A frame is cut down to the box of pixels that changed, and the pixels inside the box that did
 *   not change are written as the transparent colour (drawn over what is already there), which the
 *   LZW stage turns into long cheap runs. A frame that changes nothing is not written at all: its
 *   time is added to the frame before it.
 * - One palette for the file, or a palette per frame, by [PaletteMode].
 * - Delays are written in the GIF's hundredths of a second with the rounding error carried to the
 *   next frame, so a 15 fps animation (66.7 ms) keeps its real length. Viewers show a delay under
 *   2 hundredths as 10, so 2 (20 ms, 50 fps) is the least written.
 *
 * ## Transparency
 * GIF has one bit of it: a pixel with alpha under [AnimSequence.ALPHA_CUTOFF] is clear, anything
 * else is opaque and keeps its colour. The caller says up front ([hasTransparency]) whether any
 * frame has clear pixels. If so, every frame is written on its own (cropped to its opaque box,
 * "restore to background" after it, no delta against the last one), because a viewer cannot make a
 * drawn pixel clear again any other way; the files are larger than for opaque frames. If it says
 * no, pixels with low alpha are drawn opaque in their colour.
 *
 * Not unit-checked against any viewer other than the JDK's ImageIO reader and this package's own
 * decoder.
 *
 * @param paletteSample frames to build the file's palette from. Empty builds it from the first
 *   frame, which suits a still scene and badly suits a fade; [GifEncoder.encode] gives it every frame.
 */
class GifStreamEncoder(
    width: Int,
    height: Int,
    plays: Int,
    hasTransparency: Boolean,
    options: GifOptions,
    out: OutputStream,
    paletteSample: List<IntArray> = emptyList(),
) {
    private val w = width
    private val h = height
    private val plays = plays
    private val transparent = hasTransparency
    private val opt = options
    private val out = out

    private var histogram: ColorHistogram? = null
    private var globalColors: IntArray? = null
    private var globalLookup: PaletteLookup? = null
    private var globalBits = 0
    private var started = false
    private var finished = false

    private val tracker = if (!hasTransparency && options.deltaFrames) DeltaTracker(width, height, options.deltaTolerance) else null
    private val mask = ByteArray(width * height)
    private var previous: IntArray? = null
    private var pending: Pending? = null
    private var carryMs = 0
    private var frameCount = 0

    init {
        require(width in 1..AnimSequence.MAX_SIDE && height in 1..AnimSequence.MAX_SIDE)
        if (paletteSample.isNotEmpty() && options.paletteMode != PaletteMode.LOCAL) {
            val hist = ColorHistogram()
            for (f in paletteSample) addWhole(hist, f)
            histogram = hist
        }
    }

    internal constructor(
        width: Int,
        height: Int,
        plays: Int,
        hasTransparency: Boolean,
        options: GifOptions,
        out: OutputStream,
        histogram: ColorHistogram,
    ) : this(width, height, plays, hasTransparency, options, out) {
        this.histogram = histogram
    }

    /** Add the next frame. [pixels] is read now and not kept. May throw only what [out] throws. */
    fun addFrame(pixels: IntArray, delayMs: Int) {
        check(!finished) { "finish() was already called" }
        require(pixels.size == w * h) { "A frame is not $w x $h" }
        if (!started) start(pixels)
        val rect: PixelRect?
        if (transparent) {
            val prev = previous
            if (prev != null && prev.contentEquals(pixels)) {
                pending?.let { it.delayMs += delayMs.toLong() }
                return
            }
            previous = pixels.copyOf()
            rect = opaqueBounds(pixels, w, h, mask) ?: PixelRect(0, 0, 1, 1)
        } else if (tracker != null) {
            rect = tracker.step(pixels, mask)
            if (rect == null) {
                // Nothing to repaint: the picture stays up longer instead of being sent again.
                pending?.let { it.delayMs += delayMs.toLong() }
                return
            }
        } else {
            java.util.Arrays.fill(mask, 1)
            rect = PixelRect(0, 0, w, h)
        }
        flush()
        pending = encodeFrame(pixels, rect, delayMs)
        frameCount++
    }

    /** Write the last frame and the trailer. The stream is flushed, not closed. */
    fun finish() {
        if (finished) return
        check(started) { "A GIF needs at least one frame" }
        flush()
        out.write(0x3B)
        out.flush()
        finished = true
    }

    // ---- the header ----

    private fun addWhole(hist: ColorHistogram, f: IntArray) {
        if (transparent) {
            for (p in f) if ((p ushr 24) >= AnimSequence.ALPHA_CUTOFF) hist.add(p)
        } else {
            for (p in f) hist.add(p)
        }
    }

    private fun start(first: IntArray) {
        started = true
        if (opt.paletteMode != PaletteMode.LOCAL) {
            val hist = histogram ?: ColorHistogram().also { addWhole(it, first) }
            val pal = MedianCut.build(hist, opt.maxColors).colors
            globalColors = pal
            globalLookup = PaletteLookup(pal)
            globalBits = bitsFor(pal.size + 1)
        }
        histogram = null
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        GifBytes.le16(out, w)
        GifBytes.le16(out, h)
        val g = globalColors
        out.write((if (g != null) 0x80 or ((globalBits - 1) and 7) else 0) or 0x70)
        out.write(0) // background colour index: viewers ignore it
        out.write(0) // pixel aspect ratio: unspecified
        if (g != null) writeTable(g, globalBits)
        if (plays != 1) {
            out.write(0x21)
            out.write(0xFF)
            out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3)
            out.write(1)
            GifBytes.le16(out, if (plays == 0) 0 else minOf(plays - 1, 65_535))
            out.write(0)
        }
    }

    private fun writeTable(colors: IntArray, bits: Int) {
        val n = 1 shl bits
        for (i in 0 until n) {
            val c = if (i < colors.size) colors[i] else 0
            out.write((c shr 16) and 255)
            out.write((c shr 8) and 255)
            out.write(c and 255)
        }
    }

    // ---- one frame ----

    private class Pending(
        val rect: PixelRect,
        val localColors: IntArray?,
        val tableBits: Int,
        val transparentIndex: Int,
        val disposal: Int,
        val lzw: ByteArray,
        var delayMs: Long,
    )

    private fun encodeFrame(pixels: IntArray, rect: PixelRect, delayMs: Int): Pending {
        val area = rect.area
        var coded = 0
        for (y in rect.y0 until rect.y1) {
            var o = y * w + rect.x0
            for (x in rect.x0 until rect.x1) {
                if (mask[o].toInt() != 0) coded++
                o++
            }
        }
        val hasClear = coded < area

        // Which palette? Global unless the mode, or the fit measured below, says otherwise.
        var colors = globalColors
        var lookup = globalLookup
        var local: IntArray? = null
        val mode = opt.paletteMode
        if (mode == PaletteMode.LOCAL || colors == null) {
            local = buildLocal(pixels, rect)
        } else if (mode == PaletteMode.AUTO && coded >= MIN_LOCAL_PIXELS) {
            val mseGlobal = meanError(pixels, rect, lookup!!, colors, coded)
            if (mseGlobal > FINE_ENOUGH) {
                val cand = buildLocal(pixels, rect)
                val mseLocal = meanError(pixels, rect, PaletteLookup(cand), cand, coded)
                if (mseGlobal > mseLocal * LOCAL_GAIN + LOCAL_MARGIN) local = cand
            }
        }
        if (local != null) {
            colors = local
            lookup = PaletteLookup(local)
        }
        val pal = colors!!
        val bits = if (local != null) bitsFor(pal.size + (if (hasClear) 1 else 0)) else globalBits
        val clearIndex = if (hasClear) pal.size else -1
        val indices = ByteArray(area)
        Dither.map(pixels, w, rect.x0, rect.y0, rect.x1, rect.y1, mask, lookup!!, pal, if (hasClear) clearIndex else 0, opt.ditherStrength, indices)
        val lzw = GifLzw.encode(indices, area, maxOf(2, bits))
        return Pending(
            rect = rect,
            localColors = local,
            tableBits = bits,
            transparentIndex = clearIndex,
            disposal = if (transparent) 2 else 1,
            lzw = lzw,
            delayMs = delayMs.toLong(),
        )
    }

    private fun buildLocal(pixels: IntArray, rect: PixelRect): IntArray {
        val hist = ColorHistogram()
        hist.addRect(pixels, w, rect.x0, rect.y0, rect.x1, rect.y1, mask)
        return MedianCut.build(hist, opt.maxColors).colors
    }

    /** Mean squared RGB error, per coded pixel, of mapping the rectangle to the nearest palette colour (no dither). */
    private fun meanError(pixels: IntArray, rect: PixelRect, lookup: PaletteLookup, colors: IntArray, coded: Int): Double {
        var sum = 0L
        for (y in rect.y0 until rect.y1) {
            var o = y * w + rect.x0
            for (x in rect.x0 until rect.x1) {
                if (mask[o].toInt() != 0) {
                    val p = pixels[o]
                    sum += rgbDistance2(p, colors[lookup.nearest(p)])
                }
                o++
            }
        }
        return sum.toDouble() / coded
    }

    private fun flush() {
        val p = pending ?: return
        pending = null
        val cs = takeDelayCs(p.delayMs)
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write((p.disposal shl 2) or (if (p.transparentIndex >= 0) 1 else 0))
        GifBytes.le16(out, cs)
        out.write(if (p.transparentIndex >= 0) p.transparentIndex else 0)
        out.write(0)
        out.write(0x2C)
        GifBytes.le16(out, p.rect.x0)
        GifBytes.le16(out, p.rect.y0)
        GifBytes.le16(out, p.rect.width)
        GifBytes.le16(out, p.rect.height)
        val local = p.localColors
        out.write(if (local != null) 0x80 or ((p.tableBits - 1) and 7) else 0)
        if (local != null) writeTable(local, p.tableBits)
        out.write(maxOf(2, p.tableBits))
        GifBytes.subBlocks(out, p.lzw)
    }

    /**
     * The delay to write for a frame that lasts [ms], in hundredths of a second: rounded, with the
     * rounding left over carried to the next frame, never below [MIN_DELAY_CS] (a shorter one is
     * shown as 10 by browsers, which would slow the animation down several times over).
     */
    private fun takeDelayCs(ms: Long): Int {
        val total = ms + carryMs
        var cs = (total / 10.0).roundToInt()
        if (cs < MIN_DELAY_CS) {
            cs = MIN_DELAY_CS
            carryMs = 0
        } else {
            if (cs > MAX_DELAY_CS) cs = MAX_DELAY_CS
            carryMs = (total - cs * 10L).coerceIn(-5L, 5L).toInt()
        }
        return cs
    }

    companion object {
        const val MIN_DELAY_CS = 2
        const val MAX_DELAY_CS = 65_535

        /** A frame painting fewer pixels than this does not earn a palette of its own. */
        internal const val MIN_LOCAL_PIXELS = 1_024

        /** A global-palette error (mean squared RGB distance per pixel) below this is a mean miss of under 2 levels a channel. */
        internal const val FINE_ENOUGH = 12.0

        /** A local palette must beat the global one by this factor, plus [LOCAL_MARGIN], to pay for its 3 bytes a colour. */
        internal const val LOCAL_GAIN = 1.4
        internal const val LOCAL_MARGIN = 2.0

        /** Smallest colour-table power of two (2, 4, ... 256) holding [colors] entries, as its bit count (1 to 8). */
        internal fun bitsFor(colors: Int): Int {
            var bits = 1
            while ((1 shl bits) < colors && bits < 8) bits++
            return bits
        }
    }
}

/** Whole-animation GIF writing on top of [GifStreamEncoder]. */
object GifEncoder {

    /**
     * Encode [sequence] to GIF bytes. The file's palette is built from the first frame in full and
     * the pixels each later frame repaints, so a long still background does not outvote the part
     * that moves. [progress] is called after each frame with (frames done, total); to cancel,
     * throw from it (a coroutine's `ensureActive()` does) and nothing is left half-written because
     * the bytes only exist once this returns.
     */
    fun encode(
        sequence: AnimSequence,
        options: GifOptions = GifOptions(),
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): ByteArray {
        require(sequence.frames.isNotEmpty()) { "A GIF needs at least one frame" }
        val out = ByteArrayOutputStream(sequence.width * sequence.height / 2 + 1024)
        encodeTo(sequence, options, out, progress)
        return out.toByteArray()
    }

    fun encodeTo(
        sequence: AnimSequence,
        options: GifOptions,
        out: OutputStream,
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ) {
        require(sequence.frames.isNotEmpty()) { "A GIF needs at least one frame" }
        val transparent = sequence.hasTransparency
        val hist = if (options.paletteMode == PaletteMode.LOCAL) null else paletteHistogram(sequence, options, transparent)
        val enc = if (hist != null) {
            GifStreamEncoder(sequence.width, sequence.height, sequence.plays, transparent, options, out, hist)
        } else {
            GifStreamEncoder(sequence.width, sequence.height, sequence.plays, transparent, options, out)
        }
        val total = sequence.frames.size
        for ((i, f) in sequence.frames.withIndex()) {
            enc.addFrame(f.pixels, f.delayMs)
            progress?.invoke(i + 1, total)
        }
        enc.finish()
    }

    /** Colours of what the file will actually paint: the whole first frame, then only what each later frame changes. */
    private fun paletteHistogram(sequence: AnimSequence, options: GifOptions, transparent: Boolean): ColorHistogram {
        val hist = ColorHistogram()
        val w = sequence.width
        val h = sequence.height
        if (transparent || !options.deltaFrames) {
            for (f in sequence.frames) {
                for (p in f.pixels) if (!transparent || (p ushr 24) >= AnimSequence.ALPHA_CUTOFF) hist.add(p)
            }
            return hist
        }
        val tracker = DeltaTracker(w, h, options.deltaTolerance)
        val mask = ByteArray(w * h)
        for (f in sequence.frames) {
            val r = tracker.step(f.pixels, mask) ?: continue
            hist.addRect(f.pixels, w, r.x0, r.y0, r.x1, r.y1, mask)
        }
        return hist
    }
}
