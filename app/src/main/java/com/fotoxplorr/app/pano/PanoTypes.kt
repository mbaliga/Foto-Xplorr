package com.fotoxplorr.app.pano

/**
 * An 8-bit grey image, row-major, one byte per pixel (read it with `toInt() and 0xFF`). The panorama
 * aligner works on grey because a sweep is translation-only after the cylindrical warp and colour adds
 * cost, not information, to finding it; a working copy is about 1200 px wide, so about 1 MB.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "a grey image must not be empty" }
        require(pixels.size.toLong() == width.toLong() * height) { "pixels must hold ${width}x$height bytes" }
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * Where the SECOND image of a pair sits in the FIRST image's pixel grid: its top-left corner is at
 * ([dx], [dy]), so second(x, y) shows the same scene point as first(x + dx, y + dy). A left-to-right
 * sweep therefore has a positive [dx]; a positive [dy] means the second frame was held lower in the
 * scene. Integer pixels at the grey working size.
 *
 * [score] is the zero-mean normalised cross-correlation over the overlap at full working size (1 is a
 * perfect match, 0 is nothing in common); [overlapFraction] is the overlap area over the smaller
 * image's area. [confident] is the ONLY flag a caller may trust: when it is false the dx/dy are the
 * best guess found and must not be used to join the photos (flat sky, repeating fence, no common
 * view, or too thin an overlap).
 */
data class Offset(
    val dx: Int,
    val dy: Int,
    val score: Float,
    val overlapFraction: Float,
    val confident: Boolean,
)

/** A photo's pixel size. */
data class PanoSize(val width: Int, val height: Int)

/** A photo's top-left corner in the panorama's pixel grid. */
data class PanoPoint(val x: Int, val y: Int)
