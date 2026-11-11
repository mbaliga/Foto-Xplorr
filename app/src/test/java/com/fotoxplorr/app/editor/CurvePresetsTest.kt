package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvePresetsTest {

    @Test
    fun `every preset is valid curve input`() {
        CurvePreset.entries.forEach { preset ->
            val points = preset.curve.points
            assertEquals("${preset.name} sorted", points.sortedBy { it.x }, points)
            assertEquals("${preset.name} starts at x=0", 0f, points.first().x, 0f)
            assertEquals("${preset.name} ends at x=1", 1f, points.last().x, 0f)
            assertTrue("${preset.name} within the point limit", points.size <= ToneCurve.MAX_POINTS)
            assertTrue(
                "${preset.name} points are at least the gap apart",
                points.zipWithNext().all { (a, b) -> b.x - a.x >= ToneCurve.MIN_POINT_GAP },
            )
        }
    }

    @Test
    fun `every preset table is non-decreasing`() {
        CurvePreset.entries.forEach { preset ->
            val lut = preset.curve.toLut()
            for (v in 1..255) assertTrue("${preset.name} dipped at $v", lut[v] >= lut[v - 1])
        }
    }

    @Test
    fun `linear is the identity and the others are not`() {
        assertTrue(CurvePreset.LINEAR.curve.isIdentity)
        CurvePreset.entries.filter { it != CurvePreset.LINEAR }.forEach {
            assertTrue("${it.name} should change something", !it.curve.isIdentity)
        }
    }

    @Test
    fun `the shadow and highlight presets move the tones they are named for`() {
        val lighten = CurvePreset.LIGHTEN_SHADOWS.curve.toLut()
        val darken = CurvePreset.DARKEN_HIGHLIGHTS.curve.toLut()
        assertTrue("shadows should lift", lighten[60] > 60 + 8)
        assertTrue("whites should be left alone", lighten[255] == 255)
        assertTrue("highlights should come down", darken[200] < 200 - 8)
        assertTrue("shadows should be left alone", darken[0] == 0)
        val matte = CurvePreset.MATTE.curve.toLut()
        assertTrue(matte[0] > 20 && matte[255] < 240)
    }

    @Test
    fun `fade keeps the exact points the built-in Faded look uses`() {
        assertEquals(
            ToneCurve(listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.92f))),
            CurvePreset.FADE.curve,
        )
    }

    @Test
    fun `applying a preset to one channel changes only that channel`() {
        CurveChannel.entries.forEach { channel ->
            val before = Adjustments(exposure = 0.3f)
            val after = before.withCurve(channel, CurvePreset.STRONG_CONTRAST.curve)
            assertEquals(CurvePreset.STRONG_CONTRAST.curve, after.curveFor(channel))
            CurveChannel.entries.filter { it != channel }.forEach { other ->
                assertEquals("${other.name} untouched by $channel", before.curveFor(other), after.curveFor(other))
            }
            assertEquals(0.3f, after.exposure, 0f)
        }
    }
}
