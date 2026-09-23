package com.fotoxplorr.app.media

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-03: the pure helpers behind [decodeUpright] -- aspect-preserving, never-upscaling target
 * sizing; the classic power-of-two sample-size search; and the EXIF orientation → rotate/flip
 * mapping, all 8 defined values.
 */
class BitmapDecodingTest {

    // --- targetSize ---------------------------------------------------------------------------

    @Test
    fun `the brief's own worked example -- 9000x6000 bounded to 8192 long edge, 64M pixels`() {
        val (w, h) = targetSize(9000, 6000, DecodeLimits(maxLongEdge = 8192, maxPixels = 64_000_000L))
        assertEquals(8192, w)
        assertEquals(5461, h)
    }

    @Test
    fun `a source already within both limits is never upscaled`() {
        val (w, h) = targetSize(800, 600, DecodeLimits(maxLongEdge = 2000, maxPixels = 10_000_000L))
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `the pixel budget can bind tighter than the long-edge limit`() {
        // 4000x4000 is under the 8000 long-edge cap but its 16M pixels exceed a 4M budget.
        val (w, h) = targetSize(4000, 4000, DecodeLimits(maxLongEdge = 8000, maxPixels = 4_000_000L))
        assertEquals(2000, w)
        assertEquals(2000, h)
    }

    @Test
    fun `a portrait source scales both dimensions by the same factor`() {
        val (w, h) = targetSize(3000, 4000, DecodeLimits(maxLongEdge = 2000, maxPixels = 100_000_000L))
        assertEquals(1500, w)
        assertEquals(2000, h)
    }

    @Test
    fun `a degenerate zero-width source returns dimensions unchanged rather than crashing`() {
        val (w, h) = targetSize(0, 500, DecodeLimits(maxLongEdge = 1000, maxPixels = 1_000_000L))
        assertEquals(0, w)
        assertEquals(500, h)
    }

    // --- sampleSizeFor -------------------------------------------------------------------------

    @Test
    fun `sampleSizeFor picks the largest power of two that still meets the target`() {
        assertEquals(4, sampleSizeFor(srcW = 4000, srcH = 3000, targetW = 900, targetH = 700))
    }

    @Test
    fun `sampleSizeFor never samples below 1`() {
        assertEquals(1, sampleSizeFor(srcW = 100, srcH = 100, targetW = 100, targetH = 100))
    }

    @Test
    fun `sampleSizeFor is bounded by the tighter of the two axes`() {
        // Width alone would allow sampleSize 4 (4000/4=1000 >= 900), but height only allows 2
        // (3000/2=1500 >= 1400, while 3000/4=750 < 1400) -- the smaller of the two wins.
        assertEquals(2, sampleSizeFor(srcW = 4000, srcH = 3000, targetW = 900, targetH = 1400))
    }

    @Test
    fun `sampleSizeFor with a non-positive target returns 1`() {
        assertEquals(1, sampleSizeFor(srcW = 4000, srcH = 3000, targetW = 0, targetH = 0))
    }

    // --- orientationTransform: all 8 EXIF values ------------------------------------------------

    @Test
    fun `orientation 1 normal is the identity`() {
        assertEquals(OrientationOps(0, false), orientationTransform(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun `orientation 2 flip horizontal`() {
        assertEquals(OrientationOps(0, true), orientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
    }

    @Test
    fun `orientation 3 rotate 180`() {
        assertEquals(OrientationOps(180, false), orientationTransform(ExifInterface.ORIENTATION_ROTATE_180))
    }

    @Test
    fun `orientation 4 flip vertical`() {
        assertEquals(OrientationOps(180, true), orientationTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL))
    }

    @Test
    fun `orientation 5 transpose`() {
        assertEquals(OrientationOps(90, true), orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE))
    }

    @Test
    fun `orientation 6 rotate 90`() {
        assertEquals(OrientationOps(90, false), orientationTransform(ExifInterface.ORIENTATION_ROTATE_90))
    }

    @Test
    fun `orientation 7 transverse`() {
        assertEquals(OrientationOps(270, true), orientationTransform(ExifInterface.ORIENTATION_TRANSVERSE))
    }

    @Test
    fun `orientation 8 rotate 270`() {
        assertEquals(OrientationOps(270, false), orientationTransform(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `an undefined or unrecognized orientation is treated as already upright`() {
        assertEquals(OrientationOps(0, false), orientationTransform(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(OrientationOps(0, false), orientationTransform(999))
    }

    @Test
    fun `swapsDimensions is true only for a 90 or 270 rotation`() {
        assertEquals(false, orientationTransform(ExifInterface.ORIENTATION_NORMAL).swapsDimensions)
        assertEquals(false, orientationTransform(ExifInterface.ORIENTATION_ROTATE_180).swapsDimensions)
        assertEquals(true, orientationTransform(ExifInterface.ORIENTATION_ROTATE_90).swapsDimensions)
        assertEquals(true, orientationTransform(ExifInterface.ORIENTATION_ROTATE_270).swapsDimensions)
    }
}
