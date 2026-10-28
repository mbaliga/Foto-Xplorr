package com.fotoxplorr.app.board

import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MaskGeometryTest {

    private fun inside(mask: LayerMask, w: Float, h: Float, x: Float, y: Float) = MaskGeometry.coverage(mask, w, h, x, y) >= 1f

    private val full = LayerMask.NONE
    private val ellipse = LayerMask(MaskShape.Ellipse)

    @Test
    fun `the whole box shows inside and nothing outside`() {
        assertTrue(inside(full, 100f, 80f, 0f, 0f))
        assertTrue(inside(full, 100f, 80f, 100f, 80f))
        assertTrue(inside(full, 100f, 80f, 50f, 40f))
        assertFalse(inside(full, 100f, 80f, -0.1f, 40f))
        assertFalse(inside(full, 100f, 80f, 50f, 80.1f))
    }

    @Test
    fun `a rounded rectangle cuts its corners and keeps its sides`() {
        val m = LayerMask(MaskShape.RoundedRect(0.2f))
        assertFalse(inside(m, 100f, 100f, 3f, 3f))
        assertFalse(inside(m, 100f, 100f, 5f, 5f))
        assertTrue(inside(m, 100f, 100f, 7f, 7f))
        assertTrue(inside(m, 100f, 100f, 20f, 3f))
        assertTrue(inside(m, 100f, 100f, 3f, 50f))
    }

    @Test
    fun `a radius of one half on a square is a circle`() {
        val m = LayerMask(MaskShape.RoundedRect(0.5f))
        assertTrue(inside(m, 100f, 100f, 50f, 50f))
        assertFalse(inside(m, 100f, 100f, 2f, 2f))
        assertTrue(inside(m, 100f, 100f, 50f, 2f))
        assertTrue(inside(m, 100f, 100f, 15f, 15f))
        assertFalse(inside(m, 100f, 100f, 14f, 14f))
    }

    @Test
    fun `a radius past one half is held at one half and a negative one at square corners`() {
        assertTrue(inside(LayerMask(MaskShape.RoundedRect(5f)), 100f, 100f, 50f, 2f))
        assertFalse(inside(LayerMask(MaskShape.RoundedRect(5f)), 100f, 100f, 2f, 2f))
        assertTrue(inside(LayerMask(MaskShape.RoundedRect(-1f)), 100f, 100f, 1f, 1f))
        assertTrue(inside(LayerMask(MaskShape.RoundedRect(Float.NaN)), 100f, 100f, 1f, 1f))
    }

    @Test
    fun `an ellipse touches the middle of each side and leaves the corners`() {
        assertTrue(inside(ellipse, 200f, 100f, 100f, 50f))
        assertTrue(inside(ellipse, 200f, 100f, 1f, 50f))
        assertTrue(inside(ellipse, 200f, 100f, 100f, 1f))
        assertFalse(inside(ellipse, 200f, 100f, 2f, 2f))
        assertTrue(inside(ellipse, 200f, 100f, 150f, 93f))
        assertFalse(inside(ellipse, 200f, 100f, 150f, 95f))
    }

    @Test
    fun `the ellipse distance is exact for a circle`() {
        assertEquals(10f, MaskGeometry.signedDistance(MaskShape.Ellipse, 100f, 100f, 50f, 10f), 0.01f)
        assertEquals(-10f, MaskGeometry.signedDistance(MaskShape.Ellipse, 100f, 100f, 50f, -10f), 0.01f)
        assertEquals(50f, MaskGeometry.signedDistance(MaskShape.Ellipse, 100f, 100f, 50f, 50f), 0.01f)
    }

    @Test
    fun `the ellipse distance never reaches past the inscribed radius`() {
        assertTrue(MaskGeometry.signedDistance(MaskShape.Ellipse, 400f, 100f, 200f, 50f) <= 50f)
        assertTrue(MaskGeometry.signedDistance(MaskShape.Ellipse, 400f, 100f, 200f, 70f) <= 50f)
    }

    private val triangle = LayerMask(MaskShape.Polygon(listOf(NormPoint(0.5f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f))))

    @Test
    fun `a triangle shows its inside and nothing near the empty top corners`() {
        assertTrue(inside(triangle, 100f, 100f, 50f, 60f))
        assertTrue(inside(triangle, 100f, 100f, 50f, 99f))
        assertFalse(inside(triangle, 100f, 100f, 10f, 10f))
        assertFalse(inside(triangle, 100f, 100f, 90f, 10f))
        assertFalse(inside(triangle, 100f, 100f, 10f, 60f))
    }

    @Test
    fun `a polygon distance is the distance to the nearest edge, signed`() {
        val square = MaskShape.Polygon(listOf(NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)))
        assertEquals(30f, MaskGeometry.signedDistance(square, 100f, 100f, 30f, 50f), 0.001f)
        assertEquals(-5f, MaskGeometry.signedDistance(square, 100f, 100f, 105f, 50f), 0.001f)
        // Past a corner the nearest point is the corner itself.
        assertEquals(-5f, MaskGeometry.signedDistance(square, 100f, 100f, 103f, 104f), 0.001f)
    }

    @Test
    fun `a concave outline leaves its notch out`() {
        val c = LayerMask(
            MaskShape.Polygon(
                listOf(
                    NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 0.3f), NormPoint(0.3f, 0.3f),
                    NormPoint(0.3f, 0.7f), NormPoint(1f, 0.7f), NormPoint(1f, 1f), NormPoint(0f, 1f),
                ),
            ),
        )
        assertTrue(inside(c, 100f, 100f, 10f, 50f))
        assertTrue(inside(c, 100f, 100f, 50f, 10f))
        assertTrue(inside(c, 100f, 100f, 50f, 90f))
        assertFalse(inside(c, 100f, 100f, 70f, 50f))
    }

    @Test
    fun `an outline that crosses itself leaves a hole in the middle, even-odd`() {
        val star = LayerMask(
            MaskShape.Polygon(
                listOf(0, 2, 4, 1, 3).map { k ->
                    val a = Math.toRadians(-90.0 + 72.0 * k)
                    NormPoint((0.5 + 0.5 * cos(a)).toFloat(), (0.5 + 0.5 * sin(a)).toFloat())
                },
            ),
        )
        assertFalse(inside(star, 200f, 200f, 100f, 100f))
        // Inside a point of the star.
        assertTrue(inside(star, 200f, 200f, 100f, 25f))
    }

    @Test
    fun `fewer than three points show the whole box, not nothing`() {
        val two = LayerMask(MaskShape.Polygon(listOf(NormPoint(0f, 0f), NormPoint(1f, 1f))))
        assertTrue(inside(two, 100f, 100f, 50f, 50f))
        assertTrue(inside(LayerMask(MaskShape.Polygon(emptyList())), 100f, 100f, 50f, 50f))
    }

    @Test
    fun `a subject mask has no geometry of its own and is the whole box`() {
        val m = LayerMask(MaskShape.Subject(emptyList()))
        assertTrue(inside(m, 100f, 100f, 50f, 50f))
        assertFalse(inside(m, 100f, 100f, 150f, 50f))
    }

    @Test
    fun `a feather ramps from nothing at the edge to everything one feather in`() {
        // On a 100 box a feather of 0.1 is 10 units wide.
        val m = LayerMask(MaskShape.Full, 0.1f)
        assertEquals(0f, MaskGeometry.coverage(m, 100f, 100f, 0f, 50f), 0f)
        assertEquals(0.5f, MaskGeometry.coverage(m, 100f, 100f, 5f, 50f), 0.001f)
        assertEquals(1f, MaskGeometry.coverage(m, 100f, 100f, 10f, 50f), 0f)
        assertEquals(1f, MaskGeometry.coverage(m, 100f, 100f, 50f, 50f), 0f)
        var last = -1f
        for (i in 0..100) {
            val c = MaskGeometry.coverage(m, 100f, 100f, i / 10f, 50f)
            assertTrue("coverage must not fall going inward at $i", c >= last)
            last = c
        }
    }

    @Test
    fun `a feather works on the oval as well, in every direction alike`() {
        val m = LayerMask(MaskShape.Ellipse, 0.2f)
        val left = MaskGeometry.coverage(m, 100f, 100f, 6f, 50f)
        val top = MaskGeometry.coverage(m, 100f, 100f, 50f, 6f)
        assertEquals(left, top, 0.01f)
        assertTrue(left in 0.1f..0.9f)
    }

    @Test
    fun `a feather is held to half the short side and a nonsense one is none`() {
        val wide = LayerMask(MaskShape.Full, 0.9f)
        assertEquals(1f, MaskGeometry.coverage(wide, 100f, 100f, 50f, 50f), 0f)
        assertEquals(0.5f, MaskGeometry.coverage(wide, 100f, 100f, 25f, 50f), 0.001f)
        assertEquals(1f, MaskGeometry.coverage(LayerMask(MaskShape.Full, Float.NaN), 100f, 100f, 0.5f, 50f), 0f)
    }

    @Test
    fun `a hard edge is half shown on the edge when a pixel size is given`() {
        assertEquals(0.5f, MaskGeometry.coverage(full, 100f, 100f, 0f, 50f, pixelUnits = 2f), 0.001f)
        assertEquals(1f, MaskGeometry.coverage(full, 100f, 100f, 1f, 50f, pixelUnits = 2f), 0.001f)
        assertEquals(0f, MaskGeometry.coverage(full, 100f, 100f, -1f, 50f, pixelUnits = 2f), 0.001f)
    }

    @Test
    fun `the plane of an oval agrees with the maths at every pixel centre`() {
        val plane = MaskGeometry.plane(ellipse, 40f, 40f, 40, 40)
        assertEquals(40, plane.width)
        for (y in 0 until 40) for (x in 0 until 40) {
            val expect = MaskGeometry.coverage(ellipse, 40f, 40f, x + 0.5f, y + 0.5f, pixelUnits = 1f) * 255f
            assertEquals("pixel $x,$y", expect, plane.at(x, y).toFloat(), 1f)
        }
        assertEquals(255, plane.at(20, 20))
        assertEquals(0, plane.at(0, 0))
    }

    @Test
    fun `a plane at twice the density has the same shape`() {
        val small = MaskGeometry.plane(ellipse, 40f, 40f, 20, 20)
        val big = MaskGeometry.plane(ellipse, 40f, 40f, 80, 80)
        var covered = 0f
        var coveredBig = 0f
        for (i in small.alpha.indices) covered += (small.alpha[i].toInt() and 0xFF) / 255f
        for (i in big.alpha.indices) coveredBig += (big.alpha[i].toInt() and 0xFF) / 255f
        // Area of the oval in box units: pi * 20 * 20 = 1256.6; each plane's pixels cover (40/w)^2 units.
        assertEquals(1256.6f, covered * 4f, 13f)
        assertEquals(1256.6f, coveredBig * 0.25f, 13f)
    }

    @Test
    fun `the scanline plane of a hard polygon matches the per pixel maths`() {
        val pentagon = LayerMask(
            MaskShape.Polygon(
                (0 until 5).map { k ->
                    val a = Math.toRadians(-90.0 + 72.0 * k)
                    NormPoint((0.5 + 0.48 * cos(a)).toFloat(), (0.5 + 0.48 * sin(a)).toFloat())
                },
            ),
        )
        val plane = MaskGeometry.plane(pentagon, 60f, 60f, 60, 60)
        var worst = 0f
        for (y in 0 until 60) for (x in 0 until 60) {
            val expect = MaskGeometry.coverage(pentagon, 60f, 60f, x + 0.5f, y + 0.5f, pixelUnits = 1f) * 255f
            worst = maxOf(worst, abs(expect - plane.at(x, y)))
        }
        assertTrue("worst difference $worst", worst <= 2f)
        assertEquals(255, plane.at(30, 30))
        assertEquals(0, plane.at(1, 1))
    }

    @Test
    fun `a feathered polygon is made small and stretched`() {
        val tri = LayerMask((triangle.shape), 0.1f)
        val plane = MaskGeometry.plane(tri, 2000f, 2000f, 2000, 2000)
        assertTrue(plane.width <= MaskGeometry.SOFT_POLYGON_EDGE && plane.height <= MaskGeometry.SOFT_POLYGON_EDGE)
        assertTrue(plane.at(plane.width / 2, (plane.height * 0.7f).toInt()) > 200)
        assertEquals(0, plane.at(1, 1))
    }

    @Test
    fun `a plane from float coverage rounds to eight bits`() {
        val p = MaskGeometry.planeOf(3, 1, floatArrayOf(0f, 0.5f, 1f))
        assertEquals(0, p.at(0, 0))
        assertEquals(128, p.at(1, 0))
        assertEquals(255, p.at(2, 0))
    }

    @Test
    fun `a plane read between its pixels is bilinear and holds its edge outside`() {
        val p = MaskGeometry.planeOf(2, 1, floatArrayOf(0f, 1f))
        assertEquals(0f, p.coverageAt(0.5f, 0.5f), 0.005f)
        assertEquals(1f, p.coverageAt(1.5f, 0.5f), 0.005f)
        assertEquals(0.5f, p.coverageAt(1f, 0.5f), 0.005f)
        assertEquals(0.25f, p.coverageAt(0.75f, 0.5f), 0.005f)
        assertEquals(0f, p.coverageAt(-5f, 9f), 0.005f)
        assertEquals(1f, p.coverageAt(50f, -3f), 0.005f)
    }
}
