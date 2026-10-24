package com.fotoxplorr.app.wall

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Sets the print density in a JPEG's JFIF header, bytes in and bytes out, or on a file. Pure: no
 * Android type.
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
 *
 * A file ([stampFile], [readDpi]) is walked the same way by seeking, so only the few header bytes
 * are ever read, however big the file is: the five bytes are rewritten in place when a JFIF header
 * is there, and the insert case is streamed into a new file beside it that then replaces it. A print
 * file can be tens of MB, and the whole of it is never held in memory.
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
        return when (val found = locate(ArraySource(jpeg))) {
            null -> jpeg
            is Located.Header -> jpeg.copyOf().also { setDensity(it, found.at.toInt(), value) }
            Located.Missing -> insert(jpeg, value)
        }
    }

    /** The dots-per-inch the JFIF header states as (x, y), or null for no JFIF header, a header whose units are not dpi, or bytes that are not a JPEG. */
    fun readDpi(jpeg: ByteArray): Pair<Int, Int>? = densityOf(ArraySource(jpeg))

    /** [readDpi] for a file: only its header bytes are read. */
    fun readDpi(file: File): Pair<Int, Int>? = RandomAccessFile(file, "r").use { densityOf(FileSource(it)) }

    /**
     * Gives [file] a JFIF density of [dpi] dots per inch on both axes (clamped to 1..[MAX_DPI]), and
     * says whether it now reads back as that. A JFIF header is patched in place, five bytes; a file
     * with none gains one by a streamed copy that replaces it. A file that does not walk cleanly
     * (see above) is left as it is and the answer is false. Throws [IOException] if the file cannot be
     * written.
     */
    fun stampFile(file: File, dpi: Int): Boolean {
        val value = dpi.coerceIn(1, MAX_DPI)
        val missing = RandomAccessFile(file, "rw").use { raf ->
            when (val found = locate(FileSource(raf))) {
                null -> return false
                is Located.Header -> {
                    raf.seek(found.at + UNITS)
                    raf.write(densityBytes(value))
                    false
                }
                Located.Missing -> true
            }
        }
        if (missing) insertInto(file, value)
        return readDpi(file) == value to value
    }

    private fun densityOf(src: Source): Pair<Int, Int>? {
        val found = locate(src) as? Located.Header ?: return null
        val at = found.at
        if (src.u8(at + UNITS) != 1) return null
        return src.u16(at + X_DENSITY) to src.u16(at + Y_DENSITY)
    }

    // Offsets from the 0xFF that starts the APP0 segment.
    private const val UNITS = 11
    private const val X_DENSITY = 12
    private const val Y_DENSITY = 14

    /** The five bytes that start at [UNITS]: units 1 (dots per inch), then the x and the y density. */
    private fun densityBytes(dpi: Int): ByteArray =
        byteArrayOf(1, (dpi shr 8).toByte(), dpi.toByte(), (dpi shr 8).toByte(), dpi.toByte())

    private sealed interface Located {
        /** A JFIF APP0 segment starting at [at]. */
        class Header(val at: Long) : Located

        /** A clean walk to the scan found none. */
        object Missing : Located
    }

    /** What the walk reads: bytes by position, [u8] is -1 past the end. Over an array or a file. */
    private interface Source {
        val size: Long
        fun u8(at: Long): Int
        fun u16(at: Long): Int = (u8(at) shl 8) or u8(at + 1)
    }

    private class ArraySource(private val bytes: ByteArray) : Source {
        override val size: Long get() = bytes.size.toLong()
        override fun u8(at: Long): Int = if (at in 0 until size) bytes[at.toInt()].toInt() and 0xFF else -1
    }

    private class FileSource(private val file: RandomAccessFile) : Source {
        override val size: Long = file.length()
        override fun u8(at: Long): Int {
            if (at !in 0 until size) return -1
            file.seek(at)
            return file.read()
        }
    }

    /** Walks the segments before the scan. Null when [src] cannot be walked safely. */
    private fun locate(src: Source): Located? {
        if (src.size < 4 || src.u8(0) != FF || src.u8(1) != SOI) return null
        var pos = 2L
        while (pos + 4 <= src.size) {
            if (src.u8(pos) != FF) return null
            val marker = src.u8(pos + 1)
            when {
                // Fill bytes before a marker.
                marker == FF -> { pos++; continue }
                marker == SOS -> return Located.Missing
                // A file that ends before any scan has no picture to describe.
                marker == EOI -> return null
                // Markers without a length (TEM and the restart markers) cannot be in the headers; step over one.
                marker == 0x01 || marker in 0xD0..0xD7 -> { pos += 2; continue }
            }
            val length = src.u16(pos + 2)
            if (length < 2 || pos + 2 + length > src.size) return null
            if (marker == APP0 && length >= MIN_JFIF_LENGTH && isJfifIdentifier(src, pos + 4)) return Located.Header(pos)
            pos += 2 + length
        }
        return null
    }

    private fun isJfifIdentifier(b: Source, at: Long): Boolean =
        b.u8(at) == 0x4A && b.u8(at + 1) == 0x46 && b.u8(at + 2) == 0x49 && b.u8(at + 3) == 0x46 && b.u8(at + 4) == 0x00

    private fun setDensity(b: ByteArray, at: Int, dpi: Int) {
        densityBytes(dpi).copyInto(b, at + UNITS)
    }

    /** A new 18-byte JFIF 1.01 segment with [dpi] and no thumbnail. */
    private fun newHeader(dpi: Int): ByteArray {
        val header = ByteArray(HEADER_LENGTH)
        HEADER_START.forEachIndexed { i, v -> header[i] = v.toByte() }
        header[9] = 1 // version 1.01
        header[10] = 1
        setDensity(header, 0, dpi)
        // header[16], header[17]: no thumbnail.
        return header
    }

    /** SOI, a new 18-byte JFIF 1.01 segment with [dpi], then the rest of [jpeg]. */
    private fun insert(jpeg: ByteArray, dpi: Int): ByteArray {
        val out = ByteArray(jpeg.size + HEADER_LENGTH)
        System.arraycopy(jpeg, 0, out, 0, 2)
        System.arraycopy(newHeader(dpi), 0, out, 2, HEADER_LENGTH)
        System.arraycopy(jpeg, 2, out, 2 + HEADER_LENGTH, jpeg.size - 2)
        return out
    }

    /** [file] copied to a new file beside it with the header after the start marker, which then takes its place. */
    private fun insertInto(file: File, dpi: Int) {
        val copy = File(file.parentFile, file.name + ".part")
        try {
            file.inputStream().buffered().use { input ->
                copy.outputStream().buffered().use { out ->
                    out.write(ByteArray(2).also { check(input.read(it) == 2) { "cut off" } })
                    out.write(newHeader(dpi))
                    input.copyTo(out)
                }
            }
            Files.move(copy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            copy.delete()
            throw t
        }
    }

    private const val HEADER_LENGTH = 18
}
