package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {

    @Test
    fun `every built-in preset is a real, non-identity look`() {
        BuiltInPreset.entries.forEach { builtIn ->
            assertTrue("${builtIn.name} should not be an identity Adjustments", !builtIn.adjustments.isIdentity)
        }
    }

    @Test
    fun `built-in presets have distinct ids and are flagged as built-in`() {
        val ids = BuiltInPreset.entries.map { it.toPreset().id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(BuiltInPreset.entries.all { it.toPreset().isBuiltIn })
    }

    @Test
    fun `a user preset round-trips through JSON`() {
        val preset = Preset(id = "my-look-123", name = "My Look", adjustments = Adjustments(saturation = 0.4f, clarity = 0.2f))
        val reparsed = Preset.fromJson(parseJson(preset.toJson().stringify())!!)!!
        assertEquals(preset.id, reparsed.id)
        assertEquals(preset.name, reparsed.name)
        assertEquals(preset.adjustments, reparsed.adjustments)
        // isBuiltIn is never itself persisted -- see Preset.Companion.fromJson's own reasoning:
        // a FILE under filesDir/presets/ is by construction a user preset, never a built-in one.
        assertTrue(!reparsed.isBuiltIn)
    }

    @Test
    fun `a document missing required fields fails to parse rather than returning a blank preset`() {
        assertNull(Preset.fromJson(parseJson("""{"name":"No id"}""")!!))
        assertNull(Preset.fromJson(parseJson("""{"id":"no-name"}""")!!))
    }

    @Test
    fun `slugify produces a filesystem-safe, non-empty stem for any name`() {
        assertEquals("my-warm-look", slugify("My Warm Look"))
        assertEquals("preset", slugify("   "))
        assertEquals("preset", slugify("!!!"))
        assertNotEquals("", slugify("a/b\\c:d"))
    }
}
