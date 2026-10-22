package com.fotoxplorr.app.wall

/**
 * Sets the print density in a JPEG's JFIF header, bytes in and bytes out. Pure: no Android type.
 *
 * Why it exists: Android's JPEG encoder writes a JFIF header with no real density (units 0, a 1:1
 * aspect), and a viewer or print program that reads the JFIF density, not the Exif one, then takes
 * the file for 72 or 96 dpi and prints it huge. The export writes the Exif resolution tags too;
 * this makes the JFIF header say the same thing.
 *
 * Only header bytes ever change. The walk stops at the start of the scan (SOS), so nothing of the
 * entropy-coded picture data is read as a segment or altered; the five density bytes are rewritten
 * in place when a JFIF header is there, and an 18-byte header is added straight after the start
 * marker when none is. Anything that does not walk cleanly (not a JPEG, cut off, a segment that
 * claims more than the file holds, no scan) is returned as it came, the same array.
 */
internal object JfifDensity {
    /** Two bytes each way. */
    const val MAX_DPI = 65535

    private const val FF = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0

    /** FF E0, length 16, "JFIF", NUL: the fixed start of the header [insert]ed. */
    private val HEADER_START = intArrayOf(FF, APP0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00)

    /** Segment length that holds the fields up to and including the thumbnail size bytes. */
    private const val MIN_JFIF_LENGTH = 16

    /** [jpeg] with a JFIF density of [dpi] dots per inch on both axes (clamped to 1..[MAX_DPI]). A new array; [jpeg] is never changed. */
    fun withDpi(jpeg: ByteArray, dpi: Int): ByteArray {
        val value = dpi.coerceIn(1, MAX_DPI)
        return when (val found = locate(jpeg)) {
            null -> jpeg
            is Located.Header -> jpeg.copyOf().also { setDensity(it, found.at, value) }
            Located.Missing -> insert(jpeg, value)
        }
    }

    /** The dots-per-inch the JFIF header states as (x, y), or null for no JFIF header, a header whose units are not dpi, or bytes that are not a JPEG. */
    fun readDpi(jpeg: ByteArray): Pair<Int, Int>? {
        val found = locate(jpeg) as? Located.Header ?: return null
        val at = found.at
        if (jpeg.u8(at + UNITS) != 1) return null
        return jpeg.u16(at + X_DENSITY) to jpeg.u16(at + Y_DENSITY)
    }

    // Offsets from the 0xFF that starts the APP0 segment.
    private const val UNITS = 11
    private const val X_DENSITY = 12
    private const val Y_DENSITY = 14

    private sealed interface Located {
        /** A JFIF APP0 segment starting at [at]. */
        class Header(val at: Int) : Located

        /** A clean walk to the scan found none. */
        object Missing : Located
    }

    /** Walks the segments before the scan. Null when [jpeg] cannot be walked safely. */
    private fun locate(jpeg: ByteArray): Located? {
        if (jpeg.size < 4 || jpeg.u8(0) != FF || jpeg.u8(1) != SOI) return null
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg.u8(pos) != FF) return null
            val marker = jpeg.u8(pos + 1)
            when {
                // Fill bytes before a marker.
                marker == FF -> { pos++; continue }
                marker == SOS -> return Located.Missing
                // A file that ends before any scan has no picture to describe.
                marker == EOI -> return null
                // Markers without a length (TEM and the restart markers) cannot be in the headers; step over one.
                marker == 0x01 || marker in 0xD0..0xD7 -> { pos += 2; continue }
            }
            val length = jpeg.u16(pos + 2)
            if (length < 2 || pos + 2 + length > jpeg.size) return null
            if (marker == APP0 && length >= MIN_JFIF_LENGTH && isJfifIdentifier(jpeg, pos + 4)) return Located.Header(pos)
            pos += 2 + length
        }
        return null
    }

    private fun isJfifIdentifier(b: ByteArray, at: Int): Boolean =
        b.u8(at) == 0x4A && b.u8(at + 1) == 0x46 && b.u8(at + 2) == 0x49 && b.u8(at + 3) == 0x46 && b.u8(at + 4) == 0x00

    private fun setDensity(b: ByteArray, at: Int, dpi: Int) {
        b[at + UNITS] = 1
        b[at + X_DENSITY] = (dpi shr 8).toByte()
        b[at + X_DENSITY + 1] = dpi.toByte()
        b[at + Y_DENSITY] = (dpi shr 8).toByte()
        b[at + Y_DENSITY + 1] = dpi.toByte()
    }

    /** SOI, a new 18-byte JFIF 1.01 segment with [dpi], then the rest of [jpeg]. */
    private fun insert(jpeg: ByteArray, dpi: Int): ByteArray {
        val header = ByteArray(HEADER_LENGTH)
        HEADER_START.forEachIndexed { i, v -> header[i] = v.toByte() }
        header[9] = 1 // version 1.01
        header[10] = 1
        setDensity(header, 0, dpi)
        // header[16], header[17]: no thumbnail.
        val out = ByteArray(jpeg.size + HEADER_LENGTH)
        System.arraycopy(jpeg, 0, out, 0, 2)
        System.arraycopy(header, 0, out, 2, HEADER_LENGTH)
        System.arraycopy(jpeg, 2, out, 2 + HEADER_LENGTH, jpeg.size - 2)
        return out
    }

    private const val HEADER_LENGTH = 18

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
}
