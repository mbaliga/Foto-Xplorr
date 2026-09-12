package com.fotoxplorr.app.formats

import java.io.ByteArrayOutputStream

/**
 * A minimal GIF89a encoder: header, logical screen descriptor, one global colour table, then
 * per frame a graphic control extension + image descriptor + LZW-compressed data, and a
 * trailer.
 *
 * Pure Kotlin, no Android import -- and not merely for testability. Android has never shipped
 * an ENCODER for animated GIF: `ImageDecoder`, `Movie`, `AnimatedImageDrawable` are all decode-
 * only. So there is no platform primitive [GifTrimmer] could call for its re-encode step, and
 * the constraints on this change ruled out pulling in a whole animated-GIF library for the one
 * operation this app needs. This fills that gap by hand instead. Being free of Android types is
 * what then makes it a plain JVM unit test rather than an on-device one: a corrupt encoder here
 * is a corrupt GIF file on a real phone, and this is the only place that failure can be caught
 * without one.
 *
 * ## What this deliberately does not do
 *  - No per-pixel transparency. Every output pixel is opaque; a source frame with a
 *    transparent hole is expected to already be composited onto something solid before it
 *    reaches this encoder -- which is exactly what [GifDecoder] does when it reads a GIF that
 *    used transparency itself, so a decode-trim-encode round trip through [GifTrimmer] never
 *    exposes the gap.
 *  - One GLOBAL colour table shared by every frame (built from the union of all their pixels),
 *    not a local table per frame. Simpler, and lossless for the case this exists for: the
 *    frames handed in are a contiguous run lifted from one source GIF, so they already share a
 *    palette. It only costs quality on a frame set whose colours are wildly different from each
 *    other, which a trim of one source GIF never produces.
 */
object GifEncoder {

    private val TRAILER = byteArrayOf(0x3B)

    /**
     * Encode [frames], in order, to a complete GIF89a byte stream.
     *
     * @param loopForever write a Netscape 2.0 application extension requesting an infinite
     *   loop. False omits the extension entirely, which every decoder reads as "play once" --
     *   GIF has no way to request a specific FINITE loop count greater than one through this
     *   extension, and this app has no use for that, so it is not exposed here.
     */
    fun encode(frames: List<GifFrame>, loopForever: Boolean = true): ByteArray {
        require(frames.isNotEmpty()) { "cannot encode a GIF with zero frames" }
        val width = frames[0].width
        val height = frames[0].height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share one canvas size -- a GIF's logical screen descriptor is set " +
                "once for the whole file, not per frame"
        }

        val palette = Quantizer.buildPalette(frames)
        val out = ByteArrayOutputStream()

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLogicalScreenDescriptor(out, width, height, palette.size)
        writeColorTable(out, palette)
        if (loopForever && frames.size > 1) writeNetscapeLoopExtension(out)
        frames.forEach { frame -> writeFrame(out, frame, palette) }
        out.write(TRAILER)

        return out.toByteArray()
    }

    private fun writeLogicalScreenDescriptor(out: ByteArrayOutputStream, width: Int, height: Int, paletteSize: Int) {
        writeUInt16LE(out, width)
        writeUInt16LE(out, height)
        val sizeField = colorTableSizeField(paletteSize)
        // packed: global colour table flag(1) | colour resolution(3) | sort flag(1) | size(3).
        // Colour resolution is set equal to the table size field -- this encoder never claims a
        // source bit depth richer than the table it actually wrote.
        out.write(0x80 or (sizeField shl 4) or sizeField)
        out.write(0) // background colour index -- every frame is drawn explicitly, nothing ever shows the background
        out.write(0) // pixel aspect ratio -- unused; nothing in this app renders GIFs at non-square pixels
    }

    private fun writeColorTable(out: ByteArrayOutputStream, palette: List<Int>) {
        val entries = 1 shl (colorTableSizeField(palette.size) + 1)
        for (i in 0 until entries) {
            // Table length is a power of two per the spec; unused trailing slots (when the
            // real palette isn't itself a power of two) are padded black -- never referenced,
            // since no pixel index can point past `palette.size`.
            val rgb = palette.getOrElse(i) { 0 }
            out.write((rgb shr 16) and 0xFF)
            out.write((rgb shr 8) and 0xFF)
            out.write(rgb and 0xFF)
        }
    }

    private fun writeNetscapeLoopExtension(out: ByteArrayOutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeUInt16LE(out, 0) // 0 = loop forever, per the (de facto standard) Netscape extension
        out.write(0)
    }

    private fun writeFrame(out: ByteArrayOutputStream, frame: GifFrame, palette: List<Int>) {
        // Graphic Control Extension: delay only -- no transparency, disposal left at 0
        // ("unspecified") because every frame this encoder writes is already a full,
        // self-contained canvas (see the class KDoc), so there is nothing for a decoder to
        // dispose of between frames.
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0x00)
        writeUInt16LE(out, frame.delayCentiseconds)
        out.write(0) // transparent colour index -- unused, transparency flag above is 0
        out.write(0) // block terminator

        // Image Descriptor: full-canvas, no local colour table (every frame shares the global
        // one), not interlaced.
        out.write(0x2C)
        writeUInt16LE(out, 0)
        writeUInt16LE(out, 0)
        writeUInt16LE(out, frame.width)
        writeUInt16LE(out, frame.height)
        out.write(0x00)

        val indices = IntArray(frame.argb.size) { i -> nearestPaletteIndex(palette, frame.argb[i]) }
        val minCodeSize = (colorTableSizeField(palette.size) + 1).coerceAtLeast(2)
        out.write(minCodeSize)
        writeSubBlocks(out, LzwGif.compress(indices, minCodeSize))
    }

    private fun writeSubBlocks(out: ByteArrayOutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(255, data.size - offset)
            out.write(chunk)
            out.write(data, offset, chunk)
            offset += chunk
        }
        out.write(0) // block terminator
    }

    private fun writeUInt16LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /** GIF's own encoding of a colour-table size: the stored field N means the table holds
     *  `2^(N+1)` entries, N clamped to [0, 7] (i.e. a table of 2..256 entries). Returns the
     *  smallest N whose table can hold [paletteSize] colours. */
    private fun colorTableSizeField(paletteSize: Int): Int {
        var n = 0
        while (n < 7 && (1 shl (n + 1)) < paletteSize) n++
        return n
    }

    /** Nearest-colour palette lookup by squared Euclidean RGB distance -- brute force over (at
     *  most) 256 entries per pixel. Deliberately simple: this runs once, on demand, when a
     *  person taps "trim", not on every frame of every GIF in the gallery -- there is no
     *  rendering path anywhere in this app that calls it repeatedly. */
    private fun nearestPaletteIndex(palette: List<Int>, argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val entry = palette[i]
            val dr = r - ((entry shr 16) and 0xFF)
            val dg = g - ((entry shr 8) and 0xFF)
            val db = b - (entry and 0xFF)
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
                if (distance == 0) break // exact match -- the common case for a synthetic/limited-colour source
            }
        }
        return best
    }
}

/**
 * Median-cut colour quantisation, reducing an arbitrary set of frames to at most 256 colours --
 * the hard ceiling a GIF colour table can hold. Chosen over the simpler "most popular 256
 * colours" approach because popularity quantisation is blind to colours that are visually far
 * from anything popular (a small saturated region against a huge, near-uniform background loses
 * its colour entirely); median cut instead keeps splitting the colour space itself, so a small
 * but distinct region still earns its own palette entry.
 */
private object Quantizer {
    private const val MAX_COLORS = 256

    fun buildPalette(frames: List<GifFrame>): List<Int> {
        val counts = LinkedHashMap<Int, Int>() // insertion order -> deterministic output for equal-count ties
        for (frame in frames) {
            for (pixel in frame.argb) {
                val rgb = pixel and 0x00FFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return listOf(0)
        if (counts.size <= MAX_COLORS) return counts.keys.toList()

        val pixels = counts.map { (rgb, count) -> WeightedColor(rgb, count) }
        return medianCut(pixels, MAX_COLORS).map { bucket -> averageColor(bucket) }
    }

    private data class WeightedColor(val rgb: Int, val count: Int) {
        val r get() = (rgb shr 16) and 0xFF
        val g get() = (rgb shr 8) and 0xFF
        val b get() = rgb and 0xFF
    }

    private fun medianCut(pixels: List<WeightedColor>, maxBuckets: Int): List<List<WeightedColor>> {
        val buckets = mutableListOf(pixels)
        while (buckets.size < maxBuckets) {
            val splitIndex = buckets.indices
                .filter { buckets[it].size > 1 }
                .maxByOrNull { channelRange(buckets[it]) }
                ?: break // every remaining bucket is a single colour -- nothing left worth splitting
            val bucket = buckets.removeAt(splitIndex)
            val channel = widestChannel(bucket)
            val sorted = bucket.sortedBy { color ->
                when (channel) {
                    0 -> color.r
                    1 -> color.g
                    else -> color.b
                }
            }
            val mid = sorted.size / 2
            buckets.add(sorted.subList(0, mid))
            buckets.add(sorted.subList(mid, sorted.size))
        }
        return buckets
    }

    private fun widestChannel(bucket: List<WeightedColor>): Int {
        val rRange = bucket.maxOf { it.r } - bucket.minOf { it.r }
        val gRange = bucket.maxOf { it.g } - bucket.minOf { it.g }
        val bRange = bucket.maxOf { it.b } - bucket.minOf { it.b }
        return when (maxOf(rRange, gRange, bRange)) {
            rRange -> 0
            gRange -> 1
            else -> 2
        }
    }

    private fun channelRange(bucket: List<WeightedColor>): Int {
        val rRange = bucket.maxOf { it.r } - bucket.minOf { it.r }
        val gRange = bucket.maxOf { it.g } - bucket.minOf { it.g }
        val bRange = bucket.maxOf { it.b } - bucket.minOf { it.b }
        return maxOf(rRange, gRange, bRange)
    }

    /** Count-weighted average, so one bright outlier pixel does not pull a bucket's whole
     *  representative colour toward it the way an unweighted average would. */
    private fun averageColor(bucket: List<WeightedColor>): Int {
        var totalWeight = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (color in bucket) {
            val weight = color.count.toLong()
            totalWeight += weight
            r += color.r * weight
            g += color.g * weight
            b += color.b * weight
        }
        if (totalWeight == 0L) return 0
        return (((r / totalWeight).toInt()) shl 16) or (((g / totalWeight).toInt()) shl 8) or (b / totalWeight).toInt()
    }
}
