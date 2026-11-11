package com.fotoxplorr.core.formats.simple

/**
 * DirectDraw Surface, UNCOMPRESSED only: any 8/16/24/32-bit RGB, luminance or alpha layout described
 * by the header's channel masks (BGRA8, RGBA8, B5G6R5, A4R4G4B4, L8, L8A8, A8...), plus the DX10
 * header's plain 8-bit formats. Block-compressed formats (DXT, BC) are refused; only the first mip.
 */
internal object DdsDecoder {
    private const val DDPF_ALPHAPIXELS = 0x1
    private const val DDPF_ALPHA = 0x2
    private const val DDPF_RGB = 0x40
    private const val DDPF_LUMINANCE = 0x20
    private const val DDPF_FOURCC = 0x4

    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 128 || !b.startsWithAt(0, ascii("DDS ")) || b.le32(4) != 124L) bad("not a DDS")
        val h = b.le32(12)
        val w = b.le32(16)
        limits.checkSize(w, h)
        val flags = b.le32(80).toInt()
        var dataStart = 128
        var bits: Int
        var rMask: Long; var gMask: Long; var bMask: Long; var aMask: Long
        if (flags and DDPF_FOURCC != 0) {
            if (!b.startsWithAt(84, ascii("DX10"))) bad("compressed DDS is not supported")
            if (b.size < 148) bad("DDS truncated")
            dataStart = 148
            bits = 32
            when (b.le32(128).toInt()) {
                28 -> { rMask = 0xFF; gMask = 0xFF00; bMask = 0xFF0000; aMask = 0xFF000000 } // R8G8B8A8_UNORM
                87 -> { bMask = 0xFF; gMask = 0xFF00; rMask = 0xFF0000; aMask = 0xFF000000 } // B8G8R8A8_UNORM
                88 -> { bMask = 0xFF; gMask = 0xFF00; rMask = 0xFF0000; aMask = 0 } // B8G8R8X8_UNORM
                61 -> { bits = 8; rMask = 0xFF; gMask = 0xFF; bMask = 0xFF; aMask = 0 } // R8_UNORM (grey)
                else -> bad("DDS format not supported")
            }
        } else {
            bits = b.le32(88).toInt()
            rMask = b.le32(92); gMask = b.le32(96); bMask = b.le32(100); aMask = b.le32(104)
            if (flags and DDPF_ALPHAPIXELS == 0 && flags and DDPF_ALPHA == 0) aMask = 0
            if (flags and DDPF_LUMINANCE != 0) { gMask = rMask; bMask = rMask }
            else if (flags and DDPF_ALPHA != 0) { rMask = 0; gMask = 0; bMask = 0 }
            else if (flags and DDPF_RGB == 0) bad("DDS pixel format not supported")
        }
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) bad("DDS depth not supported")
        val bytes = bits / 8
        val width = w.toInt()
        val height = h.toInt()
        if (dataStart.toLong() + width.toLong() * height * bytes > b.size) bad("DDS data truncated")
        val out = allocate(limits, width, height)
        var p = dataStart
        for (i in 0 until width * height) {
            var v = 0L
            for (k in 0 until bytes) v = v or (b.u8(p + k).toLong() shl (8 * k))
            p += bytes
            val a = if (aMask != 0L) channel(v, aMask) else 255
            val flagsAlphaOnly = rMask == 0L && gMask == 0L && bMask == 0L
            out[i] = if (flagsAlphaOnly) argb(a, 255, 255, 255)
            else argb(a, channel(v, rMask), channel(v, gMask), channel(v, bMask))
        }
        return DecodedImage(width, height, out)
    }

    /** The bits of [v] under [mask], scaled to 0..255. */
    private fun channel(v: Long, mask: Long): Int {
        if (mask == 0L) return 0
        val shift = mask.countTrailingZeroBits()
        val max = mask ushr shift
        return (((v and mask) ushr shift) * 255 / max).toInt()
    }
}
