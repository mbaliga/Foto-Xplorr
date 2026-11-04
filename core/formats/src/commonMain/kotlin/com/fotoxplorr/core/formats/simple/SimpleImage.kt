package com.fotoxplorr.core.formats.simple

/**
 * Pixels out of one of the simple-format decoders: [argb] is `width * height` packed non-premultiplied
 * ARGB ints (the layout `android.graphics.Color` and `Bitmap.setPixels` use), row 0 at the top,
 * already upright (orientation applied).
 */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height.toLong()) {
            "pixel buffer does not match $width x $height"
        }
    }
}

/**
 * The hostile-input guards every decoder in this package applies BEFORE it allocates anything.
 *
 * @property maxPixels most pixels one decode may produce (the default is 24 MP, 96 MB of ints).
 * @property maxSide longest allowed side; a 1 x 200000000 image passes a pixel cap but not this one.
 * @property maxInputBytes largest file the provider and the Android seam will read into memory.
 * @property maxInflated most bytes a deflate stream (TIFF, PNG inside ORA/KRA/ICNS, ZIP) may expand
 *   to; this is what stops a decompression bomb. 128 MB covers the raw rows of a 24 MP 8-bit RGBA
 *   PNG (96 MB) with room to spare while raw + pixels (about 224 MB) stays inside a 256 MB app heap;
 *   24 MP at 16 bits per sample is refused (a 16-bit TIFF keeps only the high bytes, so it fits).
 */
data class SimpleLimits(
    val maxPixels: Long = 24_000_000L,
    val maxSide: Int = 32_768,
    val maxInputBytes: Long = 192L shl 20,
    val maxInflated: Long = 128L shl 20,
) {
    /** Throws [InvalidImageException] unless [width] x [height] is positive and inside the caps. */
    fun checkSize(width: Long, height: Long) {
        if (width <= 0 || height <= 0) throw InvalidImageException("empty image")
        if (width > maxSide || height > maxSide) throw InvalidImageException("image too large")
        if (width * height > maxPixels) throw InvalidImageException("image too large")
    }
}

/** A file this package cannot (or will not) decode; the message is for logs, not for people. */
class InvalidImageException(message: String) : RuntimeException(message)

sealed interface SimpleResult {
    class Success(val image: DecodedImage) : SimpleResult
    class Failure(val reason: String) : SimpleResult
}

internal fun bad(message: String): Nothing = throw InvalidImageException(message)

internal fun ByteArray.u8(o: Int): Int {
    if (o < 0 || o >= size) bad("truncated")
    return this[o].toInt() and 0xFF
}

internal fun ByteArray.le16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.be16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)
internal fun ByteArray.le32(o: Int): Long =
    u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)
internal fun ByteArray.be32(o: Int): Long =
    (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()

internal fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** True when [bytes] holds [magic] at [offset]. */
internal fun ByteArray.startsWithAt(offset: Int, magic: ByteArray): Boolean {
    if (offset < 0 || offset + magic.size > size) return false
    for (i in magic.indices) if (this[offset + i] != magic[i]) return false
    return true
}

internal fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

/** Allocates the pixel buffer only after [limits] approves the size. */
internal fun allocate(limits: SimpleLimits, width: Int, height: Int): IntArray {
    limits.checkSize(width.toLong(), height.toLong())
    return IntArray(width * height)
}

/** The EXIF/TIFF orientation values 1..8 applied to a [DecodedImage]; anything else is identity. */
internal fun DecodedImage.oriented(orientation: Int): DecodedImage {
    if (orientation !in 2..8) return this
    val swap = orientation >= 5
    val nw = if (swap) height else width
    val nh = if (swap) width else height
    val out = IntArray(argb.size)
    for (y in 0 until height) for (x in 0 until width) {
        val (nx, ny) = when (orientation) {
            2 -> (width - 1 - x) to y
            3 -> (width - 1 - x) to (height - 1 - y)
            4 -> x to (height - 1 - y)
            5 -> y to x
            6 -> (height - 1 - y) to x
            7 -> (height - 1 - y) to (width - 1 - x)
            else -> y to (width - 1 - x)
        }
        out[ny * nw + nx] = argb[y * width + x]
    }
    return DecodedImage(nw, nh, out)
}
