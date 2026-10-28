package com.fotoxplorr.app.animate

/**
 * Reads an animated WebP frame by frame into fully composited canvases: the RIFF container is parsed
 * here in pure Kotlin, and each frame's image data is handed to [decode] (on Android,
 * `BitmapFactory`) as a small stand-alone still WebP file made of the frame's own chunks.
 *
 * Why not `ImageDecoder`: on API 28 and later it opens an animated WebP, but only as an
 * `AnimatedImageDrawable`, which runs on its own clock and has no way to ask for frame N. A job
 * that picks frames by index needs the frames themselves. `BitmapFactory` has decoded still lossy
 * and lossless WebP (and lossy with alpha) since Android 4.2, so this works on API 26 and 27 as
 * well, where there is no animated decoder at all.
 *
 * ## What it handles
 * The `VP8X` header (the animation flag, the canvas size), `ANIM` (the loop count; the background
 * colour is ignored and the canvas starts clear, as for GIF), and each `ANMF` frame: its offset and
 * size, duration, blend (alpha-over, or replace) and dispose (leave, or clear to transparent) methods,
 * and its `ALPH`, `VP8` or `VP8L` data. Other chunks (colour profile, metadata) are skipped. A
 * still WebP, or any other file, is [DecodeProblem.NOT_THIS_FORMAT].
 *
 * ## Damaged files
 * Like the GIF and APNG readers, nothing here throws: [next] returns the frames it could read and
 * sets [problem] at the first thing it cannot. A file that ends inside a frame loses that frame.
 *
 * ## Delays
 * A duration of 10 ms or less comes back as 100 ms, the rule [GifReader] applies for the same
 * reason. (That browsers do this for WebP too is assumed from GIF, not checked.)
 *
 * Not checked against files from real encoders: the tests build their own containers.
 */
internal class WebpReader(
    private val data: ByteArray,
    private val limits: DecodeLimits = DecodeLimits(),
    private val decode: (ByteArray) -> RawFrame?,
) {
    var width = 0
        private set
    var height = 0
        private set

    /** Total plays, 0 for ever. */
    var plays = 0
        private set

    var problem = DecodeProblem.NONE
        private set
    var detail = ""
        private set

    private class Rec(val x: Int, val y: Int, val w: Int, val h: Int, val durationMs: Int, val dispose: Boolean, val blend: Boolean, val from: Int, val to: Int)

    private val recs = ArrayList<Rec>()
    private var scanProblem = DecodeProblem.NONE
    private var scanDetail = ""
    private var cursor = 0
    private var canvas = IntArray(0)
    private var prev: Rec? = null
    private var done = false

    init {
        parse()
    }

    private fun fail(p: DecodeProblem, why: String) {
        if (problem == DecodeProblem.NONE) {
            problem = p
            detail = why
        }
        done = true
    }

    private fun le16(at: Int): Int = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    private fun le24(at: Int): Int = le16(at) or ((data[at + 2].toInt() and 255) shl 16)
    private fun le32(at: Int): Long = le24(at).toLong() or ((data[at + 3].toLong() and 255L) shl 24)

    private fun tag(at: Int, s: String): Boolean {
        for (i in 0..3) if (data[at + i] != s[i].code.toByte()) return false
        return true
    }

    private fun parse() {
        if (data.size < 12 || !tag(0, "RIFF") || !tag(8, "WEBP")) {
            fail(DecodeProblem.NOT_THIS_FORMAT, "no RIFF WEBP header")
            return
        }
        val declaredEnd = 8L + le32(4)
        var end = data.size
        if (declaredEnd < data.size) end = declaredEnd.toInt()
        var truncated = declaredEnd > data.size
        var pos = 12
        var animated = false
        var sawHeader = false
        while (pos + 8 <= end) {
            val size = le32(pos + 4)
            val payload = pos + 8
            if (payload + size > end) {
                truncated = true
                break
            }
            val len = size.toInt()
            when {
                tag(pos, "VP8X") -> {
                    if (len < 10) {
                        scanFail(DecodeProblem.CORRUPT, "VP8X is short")
                        break
                    }
                    sawHeader = true
                    animated = (data[payload].toInt() and 0x02) != 0
                    val w = le24(payload + 4) + 1L
                    val h = le24(payload + 7) + 1L
                    if (w * h > limits.maxCanvasPixels || w > AnimSequence.MAX_SIDE || h > AnimSequence.MAX_SIDE) {
                        scanFail(DecodeProblem.TOO_LARGE, "canvas is $w x $h")
                        break
                    }
                    width = w.toInt()
                    height = h.toInt()
                }
                tag(pos, "ANIM") -> if (len >= 6) plays = le16(payload + 4)
                tag(pos, "ANMF") -> {
                    if (!sawHeader) {
                        scanFail(DecodeProblem.CORRUPT, "a frame before the header")
                        break
                    }
                    if (len < 16 + 8) {
                        scanFail(DecodeProblem.CORRUPT, "ANMF is short")
                        break
                    }
                    val x = le24(payload) * 2
                    val y = le24(payload + 3) * 2
                    val w = le24(payload + 6) + 1
                    val h = le24(payload + 9) + 1
                    val flags = data[payload + 15].toInt() and 255
                    if (x.toLong() + w > width || y.toLong() + h > height) {
                        scanFail(DecodeProblem.CORRUPT, "a frame lies outside the canvas")
                        break
                    }
                    recs.add(Rec(x, y, w, h, le24(payload + 12), (flags and 1) != 0, (flags and 2) == 0, payload + 16, payload + len))
                }
            }
            pos = payload + len + (len and 1)
        }
        if (truncated && scanProblem == DecodeProblem.NONE) scanFail(DecodeProblem.TRUNCATED, "the file ends early")
        if (!sawHeader || !animated) {
            // Nothing readable as an animation; any scan problem is moot.
            recs.clear()
            if (scanProblem == DecodeProblem.TRUNCATED || scanProblem == DecodeProblem.TOO_LARGE) fail(scanProblem, scanDetail)
            else fail(DecodeProblem.NOT_THIS_FORMAT, "not an animated WebP")
            return
        }
        if (recs.isEmpty() && scanProblem == DecodeProblem.NONE) scanFail(DecodeProblem.CORRUPT, "no frames")
    }

    private fun scanFail(p: DecodeProblem, why: String) {
        if (scanProblem == DecodeProblem.NONE) {
            scanProblem = p
            scanDetail = why
        }
    }

    /** The next composited canvas, or null at the end or at the first frame that cannot be read. */
    fun next(): AnimFrame? {
        if (done) return null
        if (cursor >= recs.size) {
            done = true
            if (scanProblem != DecodeProblem.NONE) fail(scanProblem, scanDetail)
            return null
        }
        val rec = recs[cursor++]
        val still = stillFor(rec)
        if (still == null) {
            fail(DecodeProblem.CORRUPT, "a frame has no image data")
            return null
        }
        val image = try {
            decode(still)
        } catch (e: RuntimeException) {
            null
        }
        if (image == null || image.width != rec.w || image.height != rec.h) {
            fail(if (scanProblem == DecodeProblem.TRUNCATED && cursor == recs.size) DecodeProblem.TRUNCATED else DecodeProblem.CORRUPT, "frame pixels do not decode")
            return null
        }
        if (canvas.isEmpty()) canvas = IntArray(width * height)
        prev?.let { p ->
            if (p.dispose) for (y in p.y until p.y + p.h) java.util.Arrays.fill(canvas, y * width + p.x, y * width + p.x + p.w, 0)
        }
        for (row in 0 until rec.h) {
            val at = (rec.y + row) * width + rec.x
            val from = row * rec.w
            if (rec.blend) {
                for (i in 0 until rec.w) canvas[at + i] = FrameScaler.over(image.argb[from + i], canvas[at + i])
            } else {
                System.arraycopy(image.argb, from, canvas, at, rec.w)
            }
        }
        prev = rec
        return AnimFrame(canvas.copyOf(), if (rec.durationMs <= BROWSER_MIN_MS) BROWSER_DEFAULT_MS else rec.durationMs)
    }

    /**
     * The frame's own chunks wrapped as a stand-alone WebP: `RIFF`, `WEBP`, a `VP8X` header sized to
     * the frame (alpha flagged when the frame has an `ALPH` chunk or is lossless, which may carry
     * alpha), then the chunks as they were. Null when there is no image chunk among them.
     */
    private fun stillFor(rec: Rec): ByteArray? {
        var hasImage = false
        var alpha = false
        var pos = rec.from
        while (pos + 8 <= rec.to) {
            val size = le32(pos + 4)
            if (pos + 8 + size > rec.to) break
            when {
                tag(pos, "ALPH") -> alpha = true
                tag(pos, "VP8L") -> {
                    hasImage = true
                    alpha = true
                }
                tag(pos, "VP8 ") -> hasImage = true
            }
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
        if (!hasImage) return null
        val body = rec.to - rec.from
        // RIFF chunks are padded to an even length; a writer that left the last one short is tolerated.
        val out = ByteArray(12 + 18 + body + (body and 1))
        put(out, 0, "RIFF")
        putLe32(out, 4, (out.size - 8).toLong())
        put(out, 8, "WEBP")
        put(out, 12, "VP8X")
        putLe32(out, 16, 10)
        out[20] = (if (alpha) 0x10 else 0).toByte()
        putLe24(out, 24, rec.w - 1)
        putLe24(out, 27, rec.h - 1)
        System.arraycopy(data, rec.from, out, 30, body)
        return out
    }

    private fun put(out: ByteArray, at: Int, s: String) {
        for (i in 0..3) out[at + i] = s[i].code.toByte()
    }

    private fun putLe32(out: ByteArray, at: Int, v: Long) {
        for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLe24(out: ByteArray, at: Int, v: Int) {
        for (i in 0..2) out[at + i] = (v ushr (8 * i)).toByte()
    }

    companion object {
        private const val BROWSER_MIN_MS = 10
        private const val BROWSER_DEFAULT_MS = 100
    }
}
