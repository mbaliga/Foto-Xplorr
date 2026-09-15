package com.fotoxplorr.app.editor

import kotlin.math.PI
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inscribed-rectangle maths, checked against cases whose answer is known by construction.
 *
 * This is the one piece of straighten that must never be wrong: get the crop too generous and the
 * exported photo shows a triangle of transparent nothing at a corner; get it too conservative and
 * every straighten throws away more of the photo than it has to. Both are silent -- nothing
 * crashes -- which is exactly why the geometry is pinned here rather than judged on a device.
 */
class StraightenGeometryTest {

    private fun radians(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

    @Test
    fun `zero degrees is an exact identity`() {
        // Every field in EditRecipe uses 0 as neutral so a persisted recipe keeps rendering
        // identically forever. If this were off by even a rounding error, every photo saved
        // before straighten shipped would silently re-crop the next time it was opened.
        val result = StraightenGeometry.inscribedRect(4000f, 3000f, 0f)
        assertEquals(4000f, result.width, 0f)
        assertEquals(3000f, result.height, 0f)
    }

    @Test
    fun `a negative zero angle is also an exact identity`() {
        val result = StraightenGeometry.inscribedRect(4000f, 3000f, -0f)
        assertEquals(4000f, result.width, 0f)
        assertEquals(3000f, result.height, 0f)
    }

    @Test
    fun `a 45 degree rotation of a square shrinks by the known factor`() {
        // A unit square rotated 45 degrees becomes a diamond whose vertices sit exactly on the
        // axes at distance sqrt(2)/2 from the centre -- and the largest axis-aligned SQUARE that
        // fits inside |x| + |y| <= k is a square of side k. So the answer is exactly 1/sqrt(2),
        // the textbook figure for this exact problem, not an approximation this test invented.
        val result = StraightenGeometry.inscribedRect(1f, 1f, radians(45f))
        val expected = 1f / sqrt(2f)
        assertEquals(expected, result.width, 1e-4f)
        assertEquals(expected, result.height, 1e-4f)
    }

    @Test
    fun `the result never exceeds the source in either dimension`() {
        val sizes = listOf(100f to 100f, 4000f to 3000f, 1f to 5000f, 5000f to 1f)
        val angles = listOf(0.5f, 1f, 5f, 10f, 15f, 29f, 44f, 45f, 60f, 89f).map(::radians)
        for ((w, h) in sizes) {
            for (a in angles) {
                val result = StraightenGeometry.inscribedRect(w, h, a)
                assertTrue(
                    "width ${result.width} exceeded source $w at angle $a",
                    result.width <= w + 1e-3f,
                )
                assertTrue(
                    "height ${result.height} exceeded source $h at angle $a",
                    result.height <= h + 1e-3f,
                )
            }
        }
    }

    @Test
    fun `the aspect ratio is preserved`() {
        // The whole point of cropping to the SAME aspect, rather than to the unconstrained
        // largest-area rectangle: an export that changed shape on every straighten tweak would be
        // a second, hidden crop tool wearing a rotation slider's clothes.
        val result = StraightenGeometry.inscribedRect(4000f, 3000f, radians(8f))
        assertEquals(4000f / 3000f, result.width / result.height, 1e-3f)
    }

    @Test
    fun `the crop grows tighter as the angle grows, in the typical straighten range`() {
        val zero = StraightenGeometry.inscribedRect(4000f, 3000f, radians(0f))
        val small = StraightenGeometry.inscribedRect(4000f, 3000f, radians(3f))
        val medium = StraightenGeometry.inscribedRect(4000f, 3000f, radians(8f))
        val large = StraightenGeometry.inscribedRect(4000f, 3000f, radians(15f))

        assertTrue(small.width < zero.width)
        assertTrue(medium.width < small.width)
        assertTrue(large.width < medium.width)
    }

    @Test
    fun `sign of the angle does not matter, only magnitude`() {
        // Which corner of the rotated rectangle binds flips with the sign of the angle, but the
        // amount of crop needed to clear it does not -- rotating a photo 5 degrees either way
        // throws away the same fraction of it.
        val positive = StraightenGeometry.inscribedRect(4000f, 3000f, radians(7f))
        val negative = StraightenGeometry.inscribedRect(4000f, 3000f, radians(-7f))
        assertEquals(positive.width, negative.width, 1e-3f)
        assertEquals(positive.height, negative.height, 1e-3f)
    }

    @Test
    fun `a zero-size source does not divide by zero`() {
        val result = StraightenGeometry.inscribedRect(0f, 0f, radians(10f))
        assertEquals(0f, result.width, 0f)
        assertEquals(0f, result.height, 0f)
    }

    @Test
    fun `a 90 degree rotation swaps the constraint but still does not crash or overflow`() {
        // Well outside straighten's intended -15..15 range, but the function must stay total: a
        // caller passing a bad value should get a small answer, never an exception or a rect
        // bigger than the source.
        val result = StraightenGeometry.inscribedRect(4000f, 3000f, radians(90f))
        assertTrue(result.width <= 4000f)
        assertTrue(result.height <= 3000f)
        assertTrue(result.width >= 0f && result.height >= 0f)
    }
}
