package com.fotoxplorr.core.formats.raw

/** What [JpegProbe] learned from a candidate range. [orientation] is from its own APP1 Exif, if any. */
internal data class JpegInfo(val width: Int, val height: Int, val orientation: Int?)

/**
 * The validator: a range is a usable preview only if it starts FFD8FF, walks marker by marker (a
 * bounded number of segments, never past the range) to a baseline/extended/progressive SOF with
 * plausible dimensions, and its tail holds an EOI. Lossless (SOF3, the DNG/CR2 raw payload) and
 * arithmetic SOFs are refused: they are not something BitmapFactory can show.
 */
internal object JpegProbe {
    private const val MIN_LENGTH = 64L
    private const val MIN_DIMENSION = 16
    private const val MAX_SEGMENTS = 64
    private const val MAX_FILL = 256
    private const val TAIL = 64

    fun probe(r: Reader, offset: Long, length: Long): JpegInfo? {
        if (offset < 0 || length < MIN_LENGTH || offset > r.length - length) return null
        val end = offset + length
        val head = r.bytes(offset, 3) ?: return null
        if (head.u8(0) != 0xFF || head.u8(1) != 0xD8 || head.u8(2) != 0xFF) return null
        var pos = offset + 2
        var orientation: Int? = null
        var segments = 0
        while (segments++ < MAX_SEGMENTS) {
            if (pos + 2 > end) return null
            val h = r.bytes(pos, 2) ?: return null
            if (h.u8(0) != 0xFF) return null
            var marker = h.u8(1)
            pos += 2
            var fill = 0
            while (marker == 0xFF) {
                if (++fill > MAX_FILL || pos + 1 > end) return null
                marker = (r.bytes(pos, 1) ?: return null).u8(0)
                pos += 1
            }
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) continue
            if (marker == 0xD9 || marker == 0xDA) return null // end/scan before any SOF
            if (pos + 2 > end) return null
            val len = (r.bytes(pos, 2) ?: return null).u16(0, false)
            if (len < 2 || pos + len > end) return null
            if (isSof(marker)) {
                if (marker !in 0xC0..0xC2 || len < 8) return null
                val s = r.bytes(pos + 2, 5) ?: return null
                val height = s.u16(1, false)
                val width = s.u16(3, false)
                if (s.u8(0) != 8 || width < MIN_DIMENSION || height < MIN_DIMENSION) return null
                if (!hasEoi(r, offset, length)) return null
                return JpegInfo(width, height, orientation)
            }
            if (marker == 0xE1 && orientation == null && len > 16) {
                val payload = r.bytes(pos + 2, len - 2)
                if (payload != null && payload.u8(0) == 'E'.code && payload.u8(1) == 'x'.code) {
                    orientation = Tiff.orientationIn(payload, 6)
                }
            }
            pos += len
        }
        return null
    }

    private fun isSof(m: Int) = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    private fun hasEoi(r: Reader, offset: Long, length: Long): Boolean {
        val n = minOf(TAIL.toLong(), length).toInt()
        val tail = r.bytes(offset + length - n, n) ?: return false
        for (i in 0 until n - 1) if (tail.u8(i) == 0xFF && tail.u8(i + 1) == 0xD9) return true
        return false
    }
}
