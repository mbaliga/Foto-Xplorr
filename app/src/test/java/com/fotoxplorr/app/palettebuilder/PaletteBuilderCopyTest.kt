package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** Every word the builder shows is short, plain and one sentence (owner feedback #6). */
class PaletteBuilderCopyTest {

    private val strings: Map<String, String> = PaletteBuilderCopy::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .onEach { it.isAccessible = true }
        .associate { it.name to it.get(null) as String }

    @Test
    fun `there are strings to check`() {
        assertTrue("found ${strings.size}", strings.size > 80)
    }

    @Test
    fun `no string is blank, padded or doubled up on spaces`() {
        for ((name, text) in strings) {
            assertTrue(name, text.isNotBlank())
            assertEquals(name, text.trim(), text)
            assertFalse(name, text.contains("  "))
        }
    }

    @Test
    fun `a string is short, and a sentence has one full stop at its end`() {
        for ((name, text) in strings) {
            assertTrue("$name is ${text.length} characters", text.length <= 64)
            if (text.endsWith(".")) assertFalse("$name has two sentences: $text", text.dropLast(1).contains(". "))
        }
    }

    @Test
    fun `the messages built from numbers read as plain sentences`() {
        assertEquals("Colour 2 of 5, #AABBCC, locked, picked", PaletteBuilderCopy.swatch(2, 5, "#AABBCC", locked = true, picked = true))
        assertEquals("Colour 1 of 3, #000000", PaletteBuilderCopy.swatch(1, 3, "#000000", locked = false, picked = false))
        assertEquals("Hue down 15", PaletteBuilderCopy.lower("Hue", 15))
        assertEquals("Hue up 3", PaletteBuilderCopy.raise("Hue", 3))
        assertEquals("Colour 1 on colour 2, 4.5 to 1, Good for text", PaletteBuilderCopy.contrastCell(1, 2, 4.5, "Good for text"))
        assertEquals("Colours 2 and 4 look alike to red-weak eyes.", PaletteBuilderCopy.collision(2, 4, "Red-weak eyes"))
        assertEquals("3 of 6 chosen.", PaletteBuilderCopy.chosen(3))
        assertEquals("7 of 10 pairs read well.", PaletteBuilderCopy.contrastSummary(7, 10))
        assertEquals("Palette: #000000, #FFFFFF", PaletteBuilderCopy.stripDescription(listOf("#000000", "#FFFFFF")))
    }

    @Test
    fun `the photo limit in the words is the limit the rules use`() {
        assertEquals(6, PaletteBuilderCopy.MAX_PHOTOS)
    }
}
