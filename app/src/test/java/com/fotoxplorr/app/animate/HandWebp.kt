package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream

/**
 * An animated WebP container writer for tests that shares no code with [WebpReader]: RIFF, `VP8X`,
 * `ANIM`, then `ANMF` frames whose image data is whatever chunks the test hands in. A test that only
 * checks the container uses [fakeDecoder], which reads a colour out of the first four payload bytes
 * of the frame's image chunk and the size out of the stand-alone file's `VP8X` header; the real
 * `BitmapFactory` is exercised in the Robolectric tests instead.
 */
internal class HandWebp(val width: Int, val height: Int, private val loop: Int = 0, private val animated: Boolean = true, private val withAnim: Boolean = true) {
    class Frame(
        val x: Int, val y: Int, val w: Int, val h: Int, val durationMs: Int,
        val disposeToBackground: Boolean = false, val blend: Boolean = true, val chunks: ByteArray,
    )

    private val frames = ArrayList<Frame>()
    val extra = ByteArrayOutputStream()

    fun frame(f: Frame) = apply { frames.add(f) }

    /** A frame whose image is a lossless chunk holding [argb] as its payload's first four bytes. */
    fun solid(x: Int, y: Int, w: Int, h: Int, argb: Int, durationMs: Int = 100, dispose: Boolean = false, blend: Boolean = true, lossy: Boolean = false, alphaChunk: Boolean = false) = frame(
        Frame(x, y, w, h, durationMs, dispose, blend, (if (alphaChunk) chunk("ALPH", byteArrayOf(0, 1, 2)) else ByteArray(0)) + chunk(if (lossy) "VP8 " else "VP8L", colour(argb))),
    )

    fun build(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        val vp8x = ByteArray(10)
        vp8x[0] = ((if (animated) 0x02 else 0) or 0x10).toByte()
        le24(vp8x, 4, width - 1)
        le24(vp8x, 7, height - 1)
        body.write(chunk("VP8X", vp8x))
        if (animated && withAnim) {
            val anim = ByteArray(6)
            anim[4] = (loop and 255).toByte()
            anim[5] = (loop shr 8).toByte()
            body.write(chunk("ANIM", anim))
        }
        body.write(extra.toByteArray())
        for (f in frames) {
            val p = ByteArray(16)
            le24(p, 0, f.x / 2)
            le24(p, 3, f.y / 2)
            le24(p, 6, f.w - 1)
            le24(p, 9, f.h - 1)
            le24(p, 12, f.durationMs)
            p[15] = ((if (f.disposeToBackground) 1 else 0) or (if (f.blend) 0 else 2)).toByte()
            body.write(chunk("ANMF", p + f.chunks))
        }
        val inner = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        val size = ByteArray(4)
        for (i in 0..3) size[i] = (inner.size ushr (8 * i)).toByte()
        out.write(size)
        out.write(inner)
        return out.toByteArray()
    }

    companion object {
        fun chunk(type: String, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(type.toByteArray())
            for (i in 0..3) out.write((payload.size ushr (8 * i)) and 255)
            out.write(payload)
            if (payload.size % 2 == 1) out.write(0)
            return out.toByteArray()
        }

        fun colour(argb: Int): ByteArray = byteArrayOf((argb ushr 24).toByte(), (argb ushr 16).toByte(), (argb ushr 8).toByte(), argb.toByte())

        private fun le24(b: ByteArray, at: Int, v: Int) {
            for (i in 0..2) b[at + i] = (v ushr (8 * i)).toByte()
        }

        /**
         * A stand-in for the platform decoder: the stand-alone file must be RIFF/WEBP/VP8X with the
         * frame's size in the header, followed by chunks including a `VP8 ` or `VP8L` whose first four
         * payload bytes are the colour. Anything else is null, as an undecodable file is.
         */
        fun fakeDecoder(still: ByteArray): RawFrame? {
            if (still.size < 30 || String(still, 0, 4) != "RIFF" || String(still, 8, 4) != "WEBP" || String(still, 12, 4) != "VP8X") return null
            val riff = (0..3).sumOf { (still[4 + it].toInt() and 255) shl (8 * it) }
            if (riff != still.size - 8) return null
            val w = (0..2).sumOf { (still[24 + it].toInt() and 255) shl (8 * it) } + 1
            val h = (0..2).sumOf { (still[27 + it].toInt() and 255) shl (8 * it) } + 1
            var pos = 30
            while (pos + 8 <= still.size) {
                val type = String(still, pos, 4)
                val len = (0..3).sumOf { (still[pos + 4 + it].toInt() and 255) shl (8 * it) }
                if (type == "VP8L" || type == "VP8 ") {
                    val argb = (0..3).fold(0) { acc, i -> (acc shl 8) or (still[pos + 8 + i].toInt() and 255) }
                    return RawFrame(w, h, IntArray(w * h) { argb })
                }
                pos += 8 + len + (len and 1)
            }
            return null
        }
    }
}
