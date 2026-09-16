package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditRecipeJsonTest {

    @Test
    fun `a default recipe round-trips through JSON unchanged`() {
        val recipe = EditRecipe()
        val json = parseJson(recipe.toJson().stringify())!!
        assertEquals(recipe, EditRecipe.fromJson(json))
    }

    @Test
    fun `a fully-populated recipe round-trips every field`() {
        val recipe = EditRecipe(
            quarterTurns = 3,
            flipHorizontal = true,
            straightenDegrees = -4.5f,
            adjustments = Adjustments(
                exposure = 0.7f,
                contrast = -0.2f,
                rgbCurve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.4f, 0.6f), CurvePoint(1f, 1f))),
                hsl = HslAdjustments.NONE.with(HueBand.ORANGE, HslBandAdjustment(hue = 0.3f, saturation = -0.2f, luminance = 0.1f)),
                denoise = 0.4f,
            ),
            crop = CropRect(0.1f, 0.1f, 0.9f, 0.8f),
            heals = listOf(HealSpot(0.3f, 0.4f, 0.06f), HealSpot(0.7f, 0.2f, 0.02f)),
        )

        val roundTripped = EditRecipe.fromJson(parseJson(recipe.toJson().stringify())!!)
        assertEquals(recipe, roundTripped)
    }

    @Test
    fun `a document missing newer fields decodes them at their identity value`() {
        // Simulates a recipe saved before heals/hsl existed -- old documents must still load.
        val json = parseJson(
            """{"quarterTurns":1,"flipHorizontal":false,"straightenDegrees":0,"adjustments":{"exposure":0.5},"crop":{"left":0,"top":0,"right":1,"bottom":1}}""",
        )!!
        val recipe = EditRecipe.fromJson(json)
        assertEquals(1, recipe.quarterTurns)
        assertEquals(0.5f, recipe.adjustments.exposure)
        assertTrue(recipe.adjustments.hsl.isIdentity)
        assertTrue(recipe.heals.isEmpty())
    }

    @Test
    fun `garbage JSON for a curve falls back to identity rather than crashing`() {
        val json = parseJson("""[{"x":0.3}]""")!! // only one point, and no "y"
        assertEquals(ToneCurve.IDENTITY, ToneCurve.fromJson(json))
    }

    @Test
    fun `the JSON parser round-trips strings, numbers, arrays, booleans and null`() {
        val text = """{"a":1,"b":"two","c":[1,2,3],"d":true,"e":null,"f":-2.5}"""
        val parsed = parseJson(text)!!
        assertEquals(1, parsed["a"].asInt())
        assertEquals("two", parsed["b"].asString())
        assertEquals(listOf(1, 2, 3), parsed["c"].asArray().map { it.asInt() })
        assertEquals(true, parsed["d"].asBool())
        assertEquals(-2.5f, parsed["f"].asFloat())
    }

    @Test
    fun `malformed JSON is reported as null, not a thrown exception`() {
        assertNull(parseJson("{not valid"))
        assertNull(parseJson(""))
    }

    @Test
    fun `whole numbers serialize without a trailing decimal point`() {
        assertEquals("42", 42L.toJson().stringify())
        assertEquals("3.5", 3.5f.toJson().stringify())
    }

    @Test
    fun `strings with quotes and control characters escape correctly`() {
        val json = "a \"quoted\" line\nwith a newline".toJson()
        val text = json.stringify()
        val reparsed = parseJson(text)!!
        assertEquals("a \"quoted\" line\nwith a newline", reparsed.asString())
    }
}
