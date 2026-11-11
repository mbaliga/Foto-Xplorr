package com.fotoxplorr.app.pano

/**
 * Decoded colour pixels: [width] x [height], row-major ARGB ints (`0xAARRGGBB`). The compositor
 * reads the three colour channels and treats every pixel as opaque, so a photo's own alpha is
 * ignored.
 */
class PanoPixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "decoded pixels must not be empty" }
        require(argb.size.toLong() == width.toLong() * height) { "argb must hold ${width}x$height pixels" }
    }
}

/**
 * One photo of a sweep as the compositor sees it: its size and focal length, and a way to get its
 * pixels at the size the finished canvas needs. The compositor knows nothing about files, content
 * URIs or bitmaps; the Android side supplies a source whose [decode] goes through the app's one
 * upright decoder (and re-asks the Experimental host whether the photo may still be exported, so a
 * photo that was protected after the sweep was chosen comes back null), and the tests supply
 * sources that draw procedural scenes.
 *
 * [width], [height] and [focal] describe the photo at FULL resolution, upright, in plane pixels
 * (what [PanoGeometry.focalPixels] was given and returned). They fix the photo's size on the
 * cylinder and must be the numbers the layout and the plan were made from.
 */
interface PanoSource {
    val width: Int
    val height: Int

    /** The focal length in the same pixels as [width] and [height]. */
    val focal: Double

    /**
     * The photo's pixels at [targetWidth] x [targetHeight], the full-resolution size times the
     * canvas scale ([PanoCompositor.decodeSize]). Blocking: the compositor calls it off the main
     * thread, one photo ahead of the one it is drawing, never two at once.
     *
     * The compositor tolerates a decoder that lands near the target (a power-of-two sample size, a
     * rounding) by scaling its sampling to the size it gets back, but the memory plan assumes it is
     * close, so returning more than about twice the target is a bug. Null means the photo cannot be
     * read now (deleted, protected, undecodable); the compositor stops with
     * [PanoSourceUnavailableException] rather than leave a hole in the panorama.
     */
    fun decode(targetWidth: Int, targetHeight: Int): PanoPixels?
}

/** A source returned null from [PanoSource.decode]; [index] is its position in the list the compositor was given. */
class PanoSourceUnavailableException(val index: Int) : java.io.IOException("panorama photo $index could not be read")
