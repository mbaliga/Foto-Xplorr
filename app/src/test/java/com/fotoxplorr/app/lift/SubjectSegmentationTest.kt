package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmentation, checked against images whose correct answer is known by construction -- the same
 * discipline [com.fotoxplorr.app.editor.AutoFixTest] holds AutoFix's pixel maths to, and for the
 * same reason: a real photograph only has opinions, a synthetic one has an exact answer.
 */
class SubjectSegmentationTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A field of [background], with a [size]x[size] square of [foreground] placed at ([left], [top]). */
    private fun squareOnField(
        fieldSize: Int,
        left: Int,
        top: Int,
        size: Int,
        foreground: Int,
        background: Int,
    ): IntArray = IntArray(fieldSize * fieldSize) { index ->
        val x = index % fieldSize
        val y = index / fieldSize
        if (x in left until left + size && y in top until top + size) foreground else background
    }

    // ---- exactness: the square must be lifted exactly ----

    @Test
    fun `a red square on a blue field is lifted exactly, corners included`() {
        val fieldSize = 60
        val square = 20
        val left = 15
        val top = 20
        val pixels = squareOnField(fieldSize, left, top, square, argb(255, 0, 0), argb(0, 0, 255))

        // featherRadius = 0 so the pre-feather region is checked directly, pixel for pixel --
        // feathering deliberately makes edge alpha fractional, which is a separate property
        // tested below, not something that should blur this exactness check.
        val mask = SubjectSegmentation.grow(
            pixels, fieldSize, fieldSize,
            seedX = left + square / 2, seedY = top + square / 2,
            tolerance = 10, featherRadius = 0,
        )

        for (y in 0 until fieldSize) {
            for (x in 0 until fieldSize) {
                val expected = x in left until left + square && y in top until top + square
                assertEquals(
                    "pixel ($x,$y) included=${mask.isIncluded(y * fieldSize + x)}, expected $expected",
                    expected,
                    mask.isIncluded(y * fieldSize + x),
                )
            }
        }
    }

    @Test
    fun `the bounding box of a lifted square matches the square exactly`() {
        val fieldSize = 60
        val square = 20
        val left = 10
        val top = 25
        val pixels = squareOnField(fieldSize, left, top, square, argb(10, 200, 10), argb(200, 200, 200))

        val mask = SubjectSegmentation.grow(
            pixels, fieldSize, fieldSize,
            seedX = left + 1, seedY = top + 1,
            tolerance = 10, featherRadius = 0,
        )
        val box = SubjectSegmentation.boundingBox(mask)

        assertEquals(left, box?.left)
        assertEquals(top, box?.top)
        assertEquals(left + square, box?.right)
        assertEquals(top + square, box?.bottom)
    }

    // ---- tolerance controls how far the region bleeds ----

    @Test
    fun `a low tolerance does not bleed into an adjacent similar-but-different colour`() {
        val fieldSize = 40
        val seedColour = argb(255, 0, 0)
        // Chebyshev distance from seedColour is exactly 40 in the red channel.
        val nearColour = argb(215, 0, 0)
        val farColour = argb(0, 0, 255)

        // Left half seed-coloured, right half near-coloured, seeded on the left.
        val pixels = IntArray(fieldSize * fieldSize) { index ->
            val x = index % fieldSize
            if (x < fieldSize / 2) seedColour else nearColour
        }
        val mask = SubjectSegmentation.grow(
            pixels, fieldSize, fieldSize,
            seedX = 2, seedY = fieldSize / 2,
            tolerance = 20, featherRadius = 0,
        )

        assertTrue("the seed side must be included", mask.isIncluded(fieldSize / 2 * fieldSize + 2))
        assertFalse(
            "a colour 40 levels away must NOT be included at tolerance 20",
            mask.isIncluded(fieldSize / 2 * fieldSize + (fieldSize - 2)),
        )
        // Sanity: farColour is even further and must also be excluded.
        val farPixels = IntArray(fieldSize * fieldSize) { index ->
            val x = index % fieldSize
            if (x < fieldSize / 2) seedColour else farColour
        }
        val farMask = SubjectSegmentation.grow(
            farPixels, fieldSize, fieldSize, seedX = 2, seedY = fieldSize / 2, tolerance = 20, featherRadius = 0,
        )
        assertFalse(farMask.isIncluded(fieldSize / 2 * fieldSize + (fieldSize - 2)))
    }

    @Test
    fun `a high tolerance bleeds across the same boundary a low tolerance stopped at`() {
        val fieldSize = 40
        val seedColour = argb(255, 0, 0)
        val nearColour = argb(215, 0, 0) // Chebyshev distance 40.

        val pixels = IntArray(fieldSize * fieldSize) { index ->
            val x = index % fieldSize
            if (x < fieldSize / 2) seedColour else nearColour
        }

        val tight = SubjectSegmentation.grow(pixels, fieldSize, fieldSize, 2, fieldSize / 2, tolerance = 20, featherRadius = 0)
        val loose = SubjectSegmentation.grow(pixels, fieldSize, fieldSize, 2, fieldSize / 2, tolerance = 60, featherRadius = 0)

        val farEdgeIndex = fieldSize / 2 * fieldSize + (fieldSize - 2)
        assertFalse("tolerance 20 must stop at the 40-level boundary", tight.isIncluded(farEdgeIndex))
        assertTrue("tolerance 60 must cross the same 40-level boundary", loose.isIncluded(farEdgeIndex))
    }

    // ---- feathering: soft edge, without changing the underlying shape ----

    @Test
    fun `feathering introduces fractional alpha near the edge without changing the thresholded shape`() {
        val fieldSize = 60
        val square = 20
        val left = 15
        val top = 20
        val pixels = squareOnField(fieldSize, left, top, square, argb(255, 0, 0), argb(0, 0, 255))

        val hard = SubjectSegmentation.grow(
            pixels, fieldSize, fieldSize, left + 1, top + 1, tolerance = 10, featherRadius = 0,
        )
        val feathered = SubjectSegmentation.grow(
            pixels, fieldSize, fieldSize, left + 1, top + 1, tolerance = 10, featherRadius = 2,
        )

        // Thresholding the feathered mask at 0.5 must reproduce almost exactly the same hard
        // shape: feathering softens the edge, it must not relocate it. "Almost" rather than
        // "exactly" on purpose -- a 2D box blur softens a square's CORNERS a little more than its
        // straight edges (each corner pixel loses a bit of weight on two axes at once instead of
        // one), which very slightly shrinks the thresholded corners. That is a real, expected
        // property of feathering a rectangle -- Photoshop's own "feather selection" does the same
        // thing to a marquee for the same reason -- not a bug, so the bar here is "the shapes
        // agree almost everywhere", not "corners survive a blur radius untouched".
        val agreeing = hard.alpha.indices.count { hard.isIncluded(it) == feathered.isIncluded(it) }
        val agreement = agreeing.toDouble() / hard.alpha.size
        assertTrue("feathered shape should closely track the hard shape, agreement was $agreement", agreement > 0.98)

        // The straight middle of an edge, well away from any corner, is not subject to that
        // corner-rounding at all and must match exactly.
        val edgeMidpoint = (top + square / 2) * fieldSize + left
        assertEquals(hard.isIncluded(edgeMidpoint), feathered.isIncluded(edgeMidpoint))

        // And there must be at least one pixel with a genuinely fractional alpha near the
        // boundary, or "feather" would be a word this code does not actually implement.
        val hasFraction = feathered.alpha.any { it > 0.02f && it < 0.98f }
        assertTrue("expected at least one fractional alpha pixel near the cut edge", hasFraction)

        // Deep interior and far exterior pixels must be untouched: feathering must not blur the
        // WHOLE mask, only its boundary.
        val centre = (top + square / 2) * fieldSize + (left + square / 2)
        assertEquals(1f, feathered.alpha[centre], 0.0001f)
        assertEquals(0f, feathered.alpha[0], 0.0001f) // top-left corner of the field, far from the square.
    }

    // ---- degenerate input ----

    @Test
    fun `a seed outside the image bounds produces an empty mask rather than throwing`() {
        val pixels = IntArray(100) { argb(1, 2, 3) }
        val mask = SubjectSegmentation.grow(pixels, 10, 10, seedX = 50, seedY = 50, tolerance = 10)
        assertTrue(mask.alpha.all { it == 0f })
        assertNull(SubjectSegmentation.boundingBox(mask))
    }

    @Test
    fun `a completely uniform image lifts the whole image from any seed`() {
        val pixels = IntArray(100) { argb(50, 50, 50) }
        val mask = SubjectSegmentation.grow(pixels, 10, 10, seedX = 5, seedY = 5, tolerance = 0, featherRadius = 0)
        assertTrue(mask.alpha.all { it == 1f })
    }

    @Test
    fun `zero tolerance only includes pixels identical to the seed`() {
        val pixels = intArrayOf(argb(10, 10, 10), argb(11, 10, 10), argb(10, 10, 10), argb(10, 10, 10))
        val mask = SubjectSegmentation.grow(pixels, 2, 2, seedX = 0, seedY = 0, tolerance = 0, featherRadius = 0)
        assertTrue(mask.isIncluded(0))
        assertFalse("one level off must be excluded at tolerance 0", mask.isIncluded(1))
    }
}
