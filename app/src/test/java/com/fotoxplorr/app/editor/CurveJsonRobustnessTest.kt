package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** A stored curve with out-of-range or out-of-order points must not take the recipe down. */
class CurveJsonRobustnessTest {

    @Test
    fun `an out-of-range y is clamped instead of throwing`() {
        val json = parseJson("""[{"x":0,"y":0},{"x":0.5,"y":1.2},{"x":1,"y":-0.3}]""")!!
        val curve = ToneCurve.fromJson(json)
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 1f), CurvePoint(1f, 0f)), curve.points)
    }

    @Test
    fun `out-of-order points are sorted by x`() {
        val json = parseJson("""[{"x":1,"y":1},{"x":0.6,"y":0.5},{"x":0,"y":0},{"x":0.2,"y":0.3}]""")!!
        assertEquals(listOf(0f, 0.2f, 0.6f, 1f), ToneCurve.fromJson(json).points.map { it.x })
    }

    @Test
    fun `a whole recipe with one bad curve still loads`() {
        val json = parseJson(
            """{"adjustments":{"exposure":0.5,"rgbCurve":[{"x":0,"y":0},{"x":0.5,"y":1.2},{"x":1,"y":1}]}}""",
        )!!
        val recipe = EditRecipe.fromJson(json)
        assertEquals(0.5f, recipe.adjustments.exposure)
        assertNotNull(recipe.adjustments.rgbCurve)
        assertEquals(1f, recipe.adjustments.rgbCurve.points[1].y, 0f)
    }

    @Test
    fun `a non-array curve is identity`() {
        assertEquals(ToneCurve.IDENTITY, ToneCurve.fromJson(parseJson("""{"x":1}""")!!))
    }
}
