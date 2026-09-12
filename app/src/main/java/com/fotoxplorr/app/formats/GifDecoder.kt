package com.fotoxplorr.app.formats

import java.io.ByteArrayOutputStream

/**
 * A GIF87a/89a container reader: header, logical screen descriptor, colour tables, then the
 * block stream (graphic control + image descriptor + LZW data, repeated) up to the trailer.
 * Produces a list of fully-composited [GifFrame]s, each the full logical-screen size.
 *
 * ## Why this exists instead of `android.graphics.Movie` / `ImageDecoder`
 *
 * Both are decode-only, but neither gives a caller what [GifTrimmer] actually needs: random-
 * access to frame N's pixels together with frame N's own delay. `Movie` exposes only
 * `duration()` and `setTime(ms)` -- there is no API for "how many frames, and how long is each
 * one", so recovering that means repeatedly rendering at guessed timestamps and diffing
 * bitmaps to detect a frame boundary, which is exactly the kind of fragile heuristic this app's
 * comments elsewhere warn against. `ImageDecoder`'s `AnimatedImageDrawable` is worse for this
 * purpose: it drives its own animation loop and exposes no per-frame snapshot API at all. A
 * hand-rolled reader of the (well-published, stable-since-1989) GIF spec is more code than
 * either, but it is the only way to get an exact frame list with exact delays -- which is the
 * whole feature.
 *
 * Being pure Kotlin is a second, independent win: this can run in a plain JVM unit test
 * ([GifEncoderTest]'s round trip, [GifTrimmerTest]) with no emulator and no Robolectric shadow
 * for either `Movie` or `ImageDecoder` needed.
 *
 * ## Known simplification
 *
 * Interlaced images are de-interlaced correctly (see `interlacedRowOrder`), and disposal
 * methods 0-3 are all honoured. What is NOT implemented: the rarer Plain Text extension and any
 * application extension other than a loop count are parsed as opaque sub-blocks and skipped --
 * this app only ever needs the pixels and the timing, never GIF's decorative text layer.
 */
object GifDecoder {

    data class DecodedGif(val frames: List<GifFrame>, val width: Int, val height: Int)

    fun decode(bytes: ByteArray): DecodedGif {
        val reader = ByteReader(bytes)
        val magic = reader.readAscii(6)
        require(magic == "GIF87a" || magic == "GIF89a") { "not a GIF file (header was '$magic')" }

        val width = reader.readUInt16LE()
        val height = reader.readUInt16LE()
        require(width > 0 && height > 0) { "GIF logical screen must be non-empty, got ${width}x$height" }
        val screenPacked = reader.readByte()
        val hasGlobalTable = (screenPacked and 0x80) != 0
        val globalTableSize = 1 shl ((screenPacked and 0x07) + 1)
        reader.readByte() // background colour index -- unused; this app always draws every frame explicitly
        reader.readByte() // pixel aspect ratio -- unused; nothing here renders at non-square pixels
        val globalTable = if (hasGlobalTable) readColorTable(reader, globalTableSize) else null

        // The running canvas every frame composites onto -- starts fully transparent, same as
        // a freshly opened GIF before any frame has drawn.
        val canvas = IntArray(width * height)
        val frames = mutableListOf<GifFrame>()

        var pendingDelay = 0
        var pendingTransparentIndex = -1
        var pendingDisposal = 0

        blocks@ while (true) {
            when (reader.readByte()) {
                EXTENSION_INTRODUCER -> {
                    val label = reader.readByte()
                    if (label == GRAPHIC_CONTROL_LABEL) {
                        reader.readByte() // block size, always 4 for a GCE
                        val gcePacked = reader.readByte()
                        pendingDisposal = (gcePacked shr 2) and 0x07
                        val hasTransparency = (gcePacked and 0x01) != 0
                        pendingDelay = reader.readUInt16LE()
                        val transparentIndex = reader.readByte()
                        pendingTransparentIndex = if (hasTransparency) transparentIndex else -1
                        reader.skipSubBlocks()
                    } else {
                        reader.skipSubBlocks()
                    }
                }
                IMAGE_SEPARATOR -> {
                    val left = reader.readUInt16LE()
                    val top = reader.readUInt16LE()
                    val imageWidth = reader.readUInt16LE()
                    val imageHeight = reader.readUInt16LE()
                    val imagePacked = reader.readByte()
                    val hasLocalTable = (imagePacked and 0x80) != 0
                    val interlaced = (imagePacked and 0x40) != 0
                    val localTableSize = 1 shl ((imagePacked and 0x07) + 1)
                    val table = if (hasLocalTable) {
                        readColorTable(reader, localTableSize)
                    } else {
                        globalTable ?: error("image has neither a local nor a global colour table")
                    }

                    val minCodeSize = reader.readByte()
                    val compressed = reader.readSubBlocks()
                    val indices = LzwGif.decompress(compressed, minCodeSize, imageWidth * imageHeight)

                    // Only needed if THIS frame's disposal says the next one restores to
                    // whatever the canvas looked like right before this frame drew.
                    val preDraw = if (pendingDisposal == DISPOSE_TO_PREVIOUS) canvas.copyOf() else null

                    drawOnto(
                        canvas = canvas,
                        canvasWidth = width,
                        canvasHeight = height,
                        indices = indices,
                        table = table,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        left = left,
                        top = top,
                        interlaced = interlaced,
                        transparentIndex = pendingTransparentIndex,
                    )
                    frames += GifFrame(canvas.copyOf(), width, height, pendingDelay)

                    when (pendingDisposal) {
                        DISPOSE_TO_BACKGROUND -> clearRegion(canvas, width, height, left, top, imageWidth, imageHeight)
                        DISPOSE_TO_PREVIOUS -> preDraw?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
                        // 0 (unspecified) and 1 (do not dispose): leave the canvas exactly as
                        // drawn -- the next frame paints on top of it, which is what "do not
                        // dispose" means.
                    }
                    pendingDelay = 0
                    pendingTransparentIndex = -1
                    pendingDisposal = 0
                }
                TRAILER -> break@blocks
                else -> {
                    // Not a byte this reader recognises as a valid block start. Real, well-
                    // formed GIFs never hit this; a truncated or corrupted file might, and
                    // stopping here (keeping whatever frames were already decoded) is safer
                    // than reading arbitrary bytes as if they were a block header.
                    break@blocks
                }
            }
        }

        require(frames.isNotEmpty()) { "GIF has no image frames" }
        return DecodedGif(frames, width, height)
    }

    private fun readColorTable(reader: ByteReader, entries: Int): List<Int> {
        val table = ArrayList<Int>(entries)
        repeat(entries) {
            val r = reader.readByte()
            val g = reader.readByte()
            val b = reader.readByte()
            table += (r shl 16) or (g shl 8) or b
        }
        return table
    }

    private fun drawOnto(
        canvas: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        indices: IntArray,
        table: List<Int>,
        imageWidth: Int,
        imageHeight: Int,
        left: Int,
        top: Int,
        interlaced: Boolean,
        transparentIndex: Int,
    ) {
        val rowOrder = if (interlaced) interlacedRowOrder(imageHeight) else null
        for (storedRow in 0 until imageHeight) {
            val destRow = rowOrder?.get(storedRow) ?: storedRow
            val canvasY = top + destRow
            if (canvasY < 0 || canvasY >= canvasHeight) continue
            for (x in 0 until imageWidth) {
                val index = indices.getOrElse(storedRow * imageWidth + x) { transparentIndex }
                if (index == transparentIndex) continue // leave whatever the canvas already had there
                val canvasX = left + x
                if (canvasX < 0 || canvasX >= canvasWidth) continue
                val rgb = table.getOrElse(index) { 0 }
                canvas[canvasY * canvasWidth + canvasX] = (0xFF shl 24) or rgb
            }
        }
    }

    /** GIF interlacing stores an image's rows in four passes (every 8th, then the offset-4
     *  every-8th, then every-4th, then every-2nd) rather than top to bottom, so a progressive
     *  decoder can show a rough image early. This app has no progressive/streaming display path
     *  -- it always waits for the whole frame -- so the only thing that matters is putting each
     *  stored row back at its real Y before compositing. Returns, for each row AS STORED (index
     *  = arrival order), the Y it actually belongs at. */
    private fun interlacedRowOrder(height: Int): IntArray {
        val order = IntArray(height)
        var i = 0
        var y = 0
        while (y < height) { order[i++] = y; y += 8 }
        y = 4
        while (y < height) { order[i++] = y; y += 8 }
        y = 2
        while (y < height) { order[i++] = y; y += 4 }
        y = 1
        while (y < height) { order[i++] = y; y += 2 }
        return order
    }

    private fun clearRegion(canvas: IntArray, canvasWidth: Int, canvasHeight: Int, left: Int, top: Int, w: Int, h: Int) {
        for (y in top until top + h) {
            if (y < 0 || y >= canvasHeight) continue
            for (x in left until left + w) {
                if (x < 0 || x >= canvasWidth) continue
                canvas[y * canvasWidth + x] = 0
            }
        }
    }

    private const val EXTENSION_INTRODUCER = 0x21
    private const val GRAPHIC_CONTROL_LABEL = 0xF9
    private const val IMAGE_SEPARATOR = 0x2C
    private const val TRAILER = 0x3B
    private const val DISPOSE_TO_BACKGROUND = 2
    private const val DISPOSE_TO_PREVIOUS = 3

    private class ByteReader(private val data: ByteArray) {
        private var pos = 0

        fun readByte(): Int {
            require(pos < data.size) { "unexpected end of GIF data at byte $pos" }
            return data[pos++].toInt() and 0xFF
        }

        fun readUInt16LE(): Int {
            val lo = readByte()
            val hi = readByte()
            return lo or (hi shl 8)
        }

        fun readAscii(count: Int): String {
            val chars = CharArray(count) { readByte().toChar() }
            return String(chars)
        }

        /** Reads a run of GIF "data sub-blocks" (each a length byte followed by that many
         *  bytes, terminated by a zero-length block) and concatenates their payload. Used for
         *  both image data and any extension's sub-blocks -- the sub-block framing is the same
         *  regardless of what the bytes inside mean. */
        fun readSubBlocks(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readByte()
                if (size == 0) break
                repeat(size) { out.write(readByte()) }
            }
            return out.toByteArray()
        }

        fun skipSubBlocks() {
            while (true) {
                val size = readByte()
                if (size == 0) break
                repeat(size) { readByte() }
            }
        }
    }
}
