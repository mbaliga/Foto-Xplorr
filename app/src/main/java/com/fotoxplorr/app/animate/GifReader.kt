package com.fotoxplorr.app.animate

/**
 * Reads a GIF (87a or 89a) frame by frame into fully composited canvases, in pure Kotlin. Android 8
 * and 9 have no `ImageDecoder` for GIF, and even where they do, this gives every frame as a plain
 * ARGB array the encoders and the video code can use.
 *
 * What it handles: global and local colour tables, interlaced frames (the four passes), transparency,
 * disposal methods 0 to 3 (4 to 7 are treated as 0, as viewers do), frames that do not cover the
 * canvas, frames that poke outside it (clipped), the NETSCAPE and ANIMEXTS loop extension, and
 * the odd stray zero bytes some writers leave. It ignores the background colour index, as browsers
 * do: the canvas starts clear, and "restore to background" clears to clear.
 *
 * ## Damaged files
 * Nothing here throws on bad bytes. [next] returns the frames it could read; at the first thing it
 * cannot make sense of it sets [problem] and returns null from then on. A frame whose pixel data
 * ends early or breaks is still returned, with what was decoded painted and the rest left as the
 * canvas had it, because that is what viewers show. A frame whose data merely ends before all its
 * pixels (an end-of-information code that came early) sets [problem] but reading goes on with the
 * frames after it; a cut file or a bad code still stops it.
 *
 * ## Delays
 * A GIF stores hundredths of a second. With [browserDelays] (the default) a delay of 10 ms or less
 * comes out as 100 ms, which is how every browser plays such a file and so what the person has
 * always seen; with it off the stored value is returned as is.
 *
 * Takes the whole file as bytes (a GIF is small next to the frames it holds). [plays] is only
 * known once the loop extension has been met, which writers put before the first frame, so read it
 * after the first [next] (or use [GifReader.decodeAll], which does).
 */
class GifReader(
    private val data: ByteArray,
    private val limits: DecodeLimits = DecodeLimits(),
    private val browserDelays: Boolean = true,
) {
    /** Canvas size; 0 until a readable header has been seen. */
    var width = 0
        private set
    var height = 0
        private set

    /** Total plays, 0 for ever; 1 until a loop extension says otherwise. */
    var plays = 1
        private set

    var problem = DecodeProblem.NONE
        private set
    var detail = ""
        private set

    private var pos = 0
    private var globalTable: IntArray? = null
    private var canvas = IntArray(0)
    private var done = false

    // The graphic control extension waiting for the next image.
    private var gceDisposal = 0
    private var gceTransparent = -1
    private var gceDelayCs = 0

    // The frame drawn last, for its disposal.
    private var prevDisposal = 0
    private var prevRect: PixelRect? = null
    private var prevSaved: IntArray? = null

    init {
        readHeader()
    }

    /** True while [problem] is only the note on a short frame, which a later, harder failure may replace. */
    private var soft = false

    private fun fail(p: DecodeProblem, why: String) {
        if (problem == DecodeProblem.NONE || soft) {
            problem = p
            detail = why
            soft = false
        }
        done = true
    }

    private fun u8(): Int = if (pos < data.size) data[pos++].toInt() and 255 else -1

    private fun u16(): Int {
        val a = u8()
        val b = u8()
        return if (a < 0 || b < 0) -1 else a or (b shl 8)
    }

    private fun readHeader() {
        if (data.size < 6 || !(data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte() &&
                (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) && data[5] == 'a'.code.toByte())
        ) {
            fail(DecodeProblem.NOT_THIS_FORMAT, "no GIF signature")
            return
        }
        pos = 6
        val w = u16()
        val h = u16()
        val packed = u8()
        u8() // background colour index
        u8() // aspect ratio
        if (packed < 0) {
            fail(DecodeProblem.TRUNCATED, "header cut short")
            return
        }
        if (w <= 0 || h <= 0) {
            fail(DecodeProblem.CORRUPT, "canvas has no size")
            return
        }
        if (w.toLong() * h > limits.maxCanvasPixels) {
            fail(DecodeProblem.TOO_LARGE, "canvas $w x $h is over the limit")
            return
        }
        width = w
        height = h
        if (packed and 0x80 != 0) {
            val table = readTable(1 shl ((packed and 7) + 1))
            if (table == null) {
                fail(DecodeProblem.TRUNCATED, "global colour table cut short")
                return
            }
            globalTable = table
        }
        canvas = IntArray(w * h)
    }

    private fun readTable(n: Int): IntArray? {
        if (pos + n * 3 > data.size) return null
        val t = IntArray(n)
        for (i in 0 until n) {
            t[i] = (0xFF shl 24) or ((data[pos].toInt() and 255) shl 16) or ((data[pos + 1].toInt() and 255) shl 8) or (data[pos + 2].toInt() and 255)
            pos += 3
        }
        return t
    }

    /** The next composited frame, or null at the end of the file or at the first thing that cannot be read. */
    fun next(): AnimFrame? {
        if (done) return null
        while (true) {
            val b = u8()
            when (b) {
                -1 -> {
                    fail(DecodeProblem.TRUNCATED, "no trailer")
                    return null
                }
                0x3B -> {
                    done = true
                    return null
                }
                0x00 -> continue
                0x21 -> if (!readExtension()) return null
                0x2C -> return readImage()
                else -> {
                    fail(DecodeProblem.CORRUPT, "unknown block 0x${b.toString(16)}")
                    return null
                }
            }
        }
    }

    private fun readExtension(): Boolean {
        val label = u8()
        if (label < 0) {
            fail(DecodeProblem.TRUNCATED, "extension cut short")
            return false
        }
        if (label == 0xF9) {
            val size = u8()
            if (size < 4) {
                fail(DecodeProblem.CORRUPT, "bad graphic control block")
                return false
            }
            val packed = u8()
            val delay = u16()
            val tIdx = u8()
            if (tIdx < 0) {
                fail(DecodeProblem.TRUNCATED, "graphic control cut short")
                return false
            }
            pos += size - 4
            gceDisposal = (packed shr 2) and 7
            gceTransparent = if (packed and 1 != 0) tIdx else -1
            gceDelayCs = delay
            return skipSubBlocks()
        }
        if (label == 0xFF) {
            val size = u8()
            if (size < 0 || pos + size > data.size) {
                fail(DecodeProblem.TRUNCATED, "application extension cut short")
                return false
            }
            val id = String(data, pos, size, Charsets.ISO_8859_1)
            pos += size
            val loopExt = id.startsWith("NETSCAPE2.0") || id.startsWith("ANIMEXTS1.0")
            // Sub-blocks: the first with id 1 holds the loop count.
            while (true) {
                val n = u8()
                if (n < 0) {
                    fail(DecodeProblem.TRUNCATED, "application extension cut short")
                    return false
                }
                if (n == 0) return true
                if (pos + n > data.size) {
                    pos = data.size
                    fail(DecodeProblem.TRUNCATED, "application extension cut short")
                    return false
                }
                if (loopExt && n >= 3 && data[pos].toInt() == 1) {
                    val count = (data[pos + 1].toInt() and 255) or ((data[pos + 2].toInt() and 255) shl 8)
                    plays = if (count == 0) 0 else count + 1
                }
                pos += n
            }
        }
        return skipSubBlocks()
    }

    private fun skipSubBlocks(): Boolean {
        while (true) {
            val n = u8()
            if (n < 0) {
                fail(DecodeProblem.TRUNCATED, "block cut short")
                return false
            }
            if (n == 0) return true
            if (pos + n > data.size) {
                pos = data.size
                fail(DecodeProblem.TRUNCATED, "block cut short")
                return false
            }
            pos += n
        }
    }

    private fun readImage(): AnimFrame? {
        val left = u16()
        val top = u16()
        val iw = u16()
        val ih = u16()
        val packed = u8()
        if (packed < 0) {
            fail(DecodeProblem.TRUNCATED, "image descriptor cut short")
            return null
        }
        if (iw <= 0 || ih <= 0) {
            // An empty image: nothing to draw, but it is still a frame in time.
            fail(DecodeProblem.CORRUPT, "image has no size")
            return null
        }
        if (iw.toLong() * ih > limits.maxCanvasPixels) {
            fail(DecodeProblem.TOO_LARGE, "image $iw x $ih is over the limit")
            return null
        }
        var table = globalTable
        if (packed and 0x80 != 0) {
            val t = readTable(1 shl ((packed and 7) + 1))
            if (t == null) {
                fail(DecodeProblem.TRUNCATED, "local colour table cut short")
                return null
            }
            table = t
        }
        val interlaced = packed and 0x40 != 0
        val minCode = u8()
        if (minCode < 0) {
            fail(DecodeProblem.TRUNCATED, "image data missing")
            return null
        }

        // Gather the sub-blocks into one stream.
        var stream = ByteArray(4096)
        var streamLen = 0
        var cut = false
        while (true) {
            val n = u8()
            if (n < 0) {
                cut = true
                break
            }
            if (n == 0) break
            val take = minOf(n, data.size - pos)
            if (streamLen + take > stream.size) stream = stream.copyOf(maxOf(stream.size * 2, streamLen + take))
            System.arraycopy(data, pos, stream, streamLen, take)
            streamLen += take
            pos += take
            if (take < n) {
                cut = true
                break
            }
        }

        val total = iw * ih
        val indices = ByteArray(total)
        val res = GifLzw.decode(stream, streamLen, minCode, indices, total)
        if (cut) fail(DecodeProblem.TRUNCATED, "image data cut short")
        else if (res.end == GifLzw.End.BAD_CODE) fail(DecodeProblem.CORRUPT, "bad LZW code")
        else if (res.end != GifLzw.End.FULL) {
            if (res.count == 0) {
                fail(DecodeProblem.CORRUPT, "image data ended early")
            } else if (problem == DecodeProblem.NONE) {
                // Writers that end a frame's data early exist and viewers paint the short frame and go on: so does this,
                // saying the file is damaged but not stopping.
                problem = DecodeProblem.CORRUPT
                detail = "image data ended early"
                soft = true
            }
        }
        if (res.count == 0 && problem != DecodeProblem.NONE) return null

        // Frame bookkeeping first: dispose of the last frame, remember what a disposal-3 frame must restore.
        applyPreviousDisposal()
        val clipped = PixelRect(left.coerceAtMost(width), top.coerceAtMost(height), (left + iw).coerceAtMost(width), (top + ih).coerceAtMost(height))
        prevDisposal = gceDisposal
        prevRect = clipped
        prevSaved = if (gceDisposal == 3) copyRect(clipped) else null

        // Paint, undoing interlace. Rows are placed one at a time; a short decode paints what it has.
        val colors = table
        var i = 0
        paint@ for (n in 0 until ih) {
            val cy = top + rowOf(n, ih, interlaced)
            for (rx in 0 until iw) {
                if (i >= res.count) break@paint
                val idx = indices[i++].toInt() and 255
                val cx = left + rx
                if (cx >= width || cy >= height) continue
                if (idx == gceTransparent) continue
                if (colors == null || idx >= colors.size) continue
                canvas[cy * width + cx] = colors[idx]
            }
        }
        var delayMs = gceDelayCs * 10
        if (browserDelays && delayMs <= 10) delayMs = 100
        gceDisposal = 0
        gceTransparent = -1
        gceDelayCs = 0
        return AnimFrame(canvas.copyOf(), delayMs)
    }

    private fun applyPreviousDisposal() {
        val r = prevRect ?: return
        when (prevDisposal) {
            2 -> for (y in r.y0 until r.y1) java.util.Arrays.fill(canvas, y * width + r.x0, y * width + r.x1, 0)
            3 -> prevSaved?.let { saved ->
                var k = 0
                for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) canvas[y * width + x] = saved[k++]
            }
        }
        prevRect = null
        prevSaved = null
    }

    private fun copyRect(r: PixelRect): IntArray {
        val out = IntArray(maxOf(0, r.area))
        var k = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) out[k++] = canvas[y * width + x]
        return out
    }

    /** The picture row that the [n]th stored row of an image [rows] tall belongs to. */
    private fun rowOf(n: Int, rows: Int, interlaced: Boolean): Int {
        if (!interlaced) return n
        // Pass 1: rows 0, 8, 16...; pass 2: 4, 12...; pass 3: 2, 6, 10...; pass 4: 1, 3, 5...
        var left = n
        val starts = INTERLACE_START
        val steps = INTERLACE_STEP
        for (p in 0..3) {
            val count = if (rows > starts[p]) (rows - 1 - starts[p]) / steps[p] + 1 else 0
            if (left < count) return starts[p] + left * steps[p]
            left -= count
        }
        return rows - 1
    }

    companion object {
        private val INTERLACE_START = intArrayOf(0, 4, 2, 1)
        private val INTERLACE_STEP = intArrayOf(8, 8, 4, 2)

        /**
         * Read the whole file, holding every frame, within [limits]. A file that is too big stops at the
         * limit with [DecodeProblem.TOO_LARGE] and the frames so far.
         */
        fun decodeAll(data: ByteArray, limits: DecodeLimits = DecodeLimits(), browserDelays: Boolean = true): AnimDecodeResult {
            val reader = GifReader(data, limits, browserDelays)
            val frames = ArrayList<AnimFrame>()
            var pixels = 0L
            var limitHit = false
            while (true) {
                val f = reader.next() ?: break
                if (frames.size >= limits.maxFrames || pixels + f.pixels.size > limits.maxTotalPixels) {
                    limitHit = true
                    break
                }
                frames.add(f)
                pixels += f.pixels.size
            }
            if (reader.width == 0) return AnimDecodeResult(null, reader.problem, reader.detail)
            val seq = AnimSequence(reader.width, reader.height, frames, reader.plays)
            return if (limitHit) AnimDecodeResult(seq, DecodeProblem.TOO_LARGE, "frame or size limit reached")
            else AnimDecodeResult(seq, reader.problem, reader.detail)
        }
    }
}
