package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve interpolation.
 *
 * This file exists for one assertion — `a rising curve never dips` — and everything else supports
 * it. A natural cubic spline through hand-placed control points overshoots: drag one point up and
 * the curve dips below its neighbours on the way. In a tone curve that dip is a band of pixels that
 * goes darker when the user asked for brighter, and it shows up in a photograph as haloed edges and
 * inverted patches in skies. Monotone interpolation is the fix, and it is not visible in the code
 * that it works.
 */
class ToneCurveTest {

    @Test
    fun `the default curve is the identity`() {
        assertTrue(ToneCurve.IDENTITY.isIdentity)
        val lut = ToneCurve.IDENTITY.toLut()
        for (v in 0..255) assertEquals(v, lut[v])
    }

    @Test
    fun `a rising curve never dips`() {
        // The overshoot test. These control points are the shape that breaks a natural spline: a
        // long flat run and then a sharp rise.
        val curve = ToneCurve(
            listOf(
                CurvePoint(0f, 0f),
                CurvePoint(0.4f, 0.05f),
                CurvePoint(0.5f, 0.5f),
                CurvePoint(0.6f, 0.95f),
                CurvePoint(1f, 1f),
            ),
        )
        val lut = curve.toLut()
        for (v in 1..255) {
            assertTrue(
                "curve dipped at $v: ${lut[v - 1]} -> ${lut[v]}",
                lut[v] >= lut[v - 1],
            )
        }
    }

    @Test
    fun `a curve stays inside the range its points describe`() {
        // The other face of overshoot: values above 1 or below 0 before clamping, which clamping
        // then turns into flat blown highlights.
        val curve = ToneCurve(
            listOf(CurvePoint(0f, 0.2f), CurvePoint(0.5f, 0.9f), CurvePoint(1f, 0.95f)),
        )
        val lut = curve.toLut()
        val lowest = lut.min()
        val highest = lut.max()
        assertTrue("dipped below its lowest point: $lowest", lowest >= (0.2f * 255).toInt() - 1)
        assertTrue("rose above its highest point: $highest", highest <= (0.95f * 255).toInt() + 1)
    }

    @Test
    fun `the curve passes through its own control points`() {
        val curve = ToneCurve(
            listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.6f), CurvePoint(1f, 1f)),
        )
        val lut = curve.toLut()
        val at = (0.25f * 255).toInt()
        assertEquals("must hit the point it was given", (0.6f * 255).toInt(), lut[at], 3)
    }

    @Test
    fun `a falling curve inverts monotonically`() {
        val curve = ToneCurve(listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)))
        val lut = curve.toLut()
        for (v in 1..255) {
            assertTrue("inverted curve rose at $v", lut[v] <= lut[v - 1])
        }
        assertEquals(255, lut[0])
        assertEquals(0, lut[255])
    }

    @Test
    fun `a flat segment stays flat`() {
        // Two points at the same height must not bulge between them: that is the local-extremum
        // case, where the tangent has to be forced to zero.
        val curve = ToneCurve(
            listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.5f), CurvePoint(0.7f, 0.5f), CurvePoint(1f, 1f)),
        )
        val lut = curve.toLut()
        val target = (0.5f * 255).toInt()
        for (v in (0.35f * 255).toInt()..(0.65f * 255).toInt()) {
            assertEquals("bulged at $v", target, lut[v], 2)
        }
    }

    @Test
    fun `the ends are pinned to the outermost points`() {
        val curve = ToneCurve(listOf(CurvePoint(0.2f, 0.3f), CurvePoint(0.8f, 0.7f)))
        val lut = curve.toLut()
        assertEquals("below the first point", (0.3f * 255).toInt(), lut[0], 2)
        assertEquals("above the last point", (0.7f * 255).toInt(), lut[255], 2)
    }

    // ---- editing ----

    @Test
    fun `adding a point keeps the list sorted`() {
        val curve = ToneCurve.IDENTITY.withPoint(CurvePoint(0.5f, 0.7f))
        assertEquals(curve.points.sortedBy { it.x }, curve.points)
        assertEquals(3, curve.points.size)
    }

    @Test
    fun `dragging a point replaces it rather than stacking points on top of each other`() {
        val curve = ToneCurve.IDENTITY
            .withPoint(CurvePoint(0.5f, 0.7f))
            .withPoint(CurvePoint(0.505f, 0.8f))
        assertEquals("a drag must move the point, not add a second", 3, curve.points.size)
        assertEquals(0.8f, curve.points.first { it.x > 0.4f && it.x < 0.6f }.y, 1e-5f)
    }

    @Test
    fun `the endpoints cannot be removed`() {
        // A curve with no value at 0 or 1 has nothing to say about black or white, and every
        // consumer would need its own fallback.
        val curve = ToneCurve.IDENTITY.withoutPointAt(0f).withoutPointAt(1f)
        assertEquals(2, curve.points.size)
        assertTrue(curve.isIdentity)
    }

    @Test
    fun `an interior point can be removed`() {
        val curve = ToneCurve.IDENTITY.withPoint(CurvePoint(0.5f, 0.7f)).withoutPointAt(0.5f)
        assertEquals(2, curve.points.size)
        assertTrue(curve.isIdentity)
    }

    @Test
    fun `a curve with fewer than two points is refused`() {
        // Rather than silently producing an identity, which would look like the user's edit was
        // ignored.
        try {
            ToneCurve(listOf(CurvePoint(0.5f, 0.5f)))
            error("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("two points"))
        }
    }

    @Test
    fun `points outside the unit square are refused`() {
        try {
            CurvePoint(1.5f, 0.5f)
            error("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("normalised"))
        }
    }

    @Test
    fun `the identity curve does not allocate a new table`() {
        // Every photo that has never been curve-edited goes through this path.
        assertTrue(ToneCurve.IDENTITY.toLut() === ToneCurve.IDENTITY_LUT)
    }

    private fun assertEquals(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected +/- $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun `unsorted input is still interpolated in order`() {
        // The editor sorts, but nothing in the type does, and a caller restoring a persisted curve
        // could hand these over in any order.
        val curve = ToneCurve(listOf(CurvePoint(1f, 1f), CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f)))
        val lut = curve.toLut()
        for (v in 1..255) assertTrue("dipped at $v", lut[v] >= lut[v - 1])
        assertFalse(curve.isIdentity)
    }
}
