package com.fotoxplorr.app.viewer.deepzoom

import com.fotoxplorr.app.media.orientationTransform
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inverse mapping is checked against the FORWARD transform the app applies (rotate clockwise,
 * then flip horizontally): every pixel of an upright rectangle, mapped back to the raw grid and
 * forward again, must land inside that rectangle -- for all eight EXIF orientations.
 */
class RegionRectsTest {
    private val rawW = 40
    private val rawH = 30

    /** Forward: raw pixel (x, y) -> upright pixel. */
    private fun forward(x: Int, y: Int, deg: Int, flip: Boolean): Pair<Int, Int> {
        var (px, py) = x to y
        var w = rawW
        var h = rawH
        when (deg) {
            90 -> { val nx = h - 1 - py; val ny = px; px = nx; py = ny; w = rawH; h = rawW }
            180 -> { px = w - 1 - px; py = h - 1 - py }
            270 -> { val nx = py; val ny = w - 1 - px; px = nx; py = ny; w = rawH; h = rawW }
        }
        if (flip) px = w - 1 - px
        return px to py
    }

    @Test
    fun `every orientation maps an upright rectangle onto exactly its raw pixels`() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
        )
        for (o in orientations) {
            val ops = orientationTransform(o)
            val (uw, uh) = RegionRects.uprightSize(rawW, rawH, ops)
            val rect = SourceRect(3, 5, minOf(uw, 17), minOf(uh, 12))
            val raw = RegionRects.toRaw(rect, rawW, rawH, ops)
            var inside = 0
            for (y in raw.top until raw.bottom) for (x in raw.left until raw.right) {
                val (ux, uy) = forward(x, y, ops.rotationDegrees, ops.flipHorizontal)
                assertEquals("orientation $o: raw ($x,$y) left the rect", true,
                    ux in rect.left until rect.right && uy in rect.top until rect.bottom)
                inside++
            }
            assertEquals("orientation $o: pixel count", rect.width * rect.height, inside)
        }
    }

    @Test
    fun `the whole upright image maps to the whole raw grid`() {
        val ops = orientationTransform(ExifInterface.ORIENTATION_ROTATE_90)
        val (uw, uh) = RegionRects.uprightSize(rawW, rawH, ops)
        assertEquals(SourceRect(0, 0, rawW, rawH), RegionRects.toRaw(SourceRect(0, 0, uw, uh), rawW, rawH, ops))
    }
}
