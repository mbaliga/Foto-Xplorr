package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale

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
    fun `a contrast shown as 4 point 5 is never below the 4 point 5 a tick needs`() {
        assertEquals("Colour 1 on colour 2, 4.4 to 1, Hard to read", PaletteBuilderCopy.contrastCell(1, 2, 4.47, "Hard to read"))
        assertEquals("Colour 1 on colour 2, 4.4 to 1, Hard to read", PaletteBuilderCopy.contrastCell(1, 2, 4.4999999, "Hard to read"))
        assertEquals("Colour 1 on colour 2, 4.5 to 1, Reads well", PaletteBuilderCopy.contrastCell(1, 2, 4.5, "Reads well"))
        assertEquals("Colour 1 on colour 2, 7.0 to 1, Reads well", PaletteBuilderCopy.contrastCell(1, 2, 7.0, "Reads well"))
        assertEquals("Colour 1 on colour 2, 21.0 to 1, Reads well", PaletteBuilderCopy.contrastCell(1, 2, 21.0, "Reads well"))
        assertEquals("Colour 1 on colour 2, 1.0 to 1, Hard to read", PaletteBuilderCopy.contrastCell(1, 2, 1.0, "Hard to read"))
        // The shown number and the verdict agree for pairs just under and over the line.
        for (i in 0..200) {
            val ratio = 4.3 + i * 0.001
            val shown = Regex("""(\d+\.\d) to 1""").find(PaletteBuilderCopy.contrastCell(1, 2, ratio, ""))!!.groupValues[1].toDouble()
            assertEquals("$ratio shown $shown", ratio >= 4.5, shown >= 4.5)
        }
    }

    @Test
    fun `numbers in the words are Latin digits whatever the phone's language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("Colour 1 on colour 2, 4.5 to 1, Reads well", PaletteBuilderCopy.contrastCell(1, 2, 4.5, "Reads well"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `the colour-blind all-clear claims only what is checked`() {
        assertFalse(PaletteBuilderCopy.VISION_ALL_CLEAR.contains("every", ignoreCase = true))
        assertTrue(PaletteBuilderCopy.VISION_ALL_CLEAR.contains("three"))
    }

    @Test
    fun `a missing source photo is not said to be protected when it may only be gone`() {
        assertTrue(PaletteBuilderCopy.SOURCE_PROTECTED.contains("missing"))
    }

    @Test
    fun `the photo limit in the words is the limit the rules use`() {
        assertEquals(6, PaletteBuilderCopy.MAX_PHOTOS)
    }
}
