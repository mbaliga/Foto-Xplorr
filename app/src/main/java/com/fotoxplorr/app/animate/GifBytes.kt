package com.fotoxplorr.app.animate

import java.io.OutputStream

/** Byte-level helpers for writing the GIF container. */
internal object GifBytes {
    fun le16(out: OutputStream, v: Int) {
        out.write(v and 255)
        out.write((v shr 8) and 255)
    }

    /** [data] cut into sub-blocks of at most 255 bytes, each led by its length, then the zero terminator. */
    fun subBlocks(out: OutputStream, data: ByteArray) {
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n)
            out.write(data, pos, n)
            pos += n
        }
        out.write(0)
    }
}
