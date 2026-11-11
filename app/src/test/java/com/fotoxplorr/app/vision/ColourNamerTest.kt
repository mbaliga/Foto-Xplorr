package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Naming: canonical colours, the table's shape, and the "seems" qualifier. */
class ColourNamerTest {

    @Test
    fun `canonical colours get their plain names`() {
        val cases = listOf(
            argb(0, 0, 0) to "black",
            argb(255, 255, 255) to "white",
            argb(128, 128, 128) to "grey",
            argb(255, 0, 0) to "red",
            argb(255, 255, 0) to "yellow",
            argb(0, 255, 255) to "cyan",
            argb(255, 165, 0) to "orange",
            argb(128, 0, 128) to "purple",
            argb(139, 69, 19) to "brown",
            argb(0, 128, 0) to "green",
            argb(0, 0, 128) to "navy",
        )
        for ((c, want) in cases) assertEquals("rgb ${VisionTestKit.r(c)},${VisionTestKit.g(c)},${VisionTestKit.b(c)}", want, ColourNamer.name(c).name)
    }

    @Test
    fun `pure blue and pure green name as a blue and a green`() {
        assertTrue(ColourNamer.name(argb(0, 0, 255)).name.contains("blue"))
        assertTrue(ColourNamer.name(argb(0, 255, 0)).name.contains("green"))
    }

    @Test
    fun `the table is about 160 distinct plain names with parseable colours`() {
        val names = ColourNamer.names
        assertTrue("${names.size} names", names.size in 140..180)
        assertEquals(names.size, names.toSet().size)
        for (line in VisionCopy.COLOUR_TABLE.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split('|')
            assertEquals(line, 2, parts.size)
            assertEquals(line, 6, parts[1].length)
            parts[1].toInt(16)
            assertTrue(line, parts[0].all { it.isLowerCase() || it == ' ' })
        }
    }

    @Test
    fun `every table colour names itself`() {
        for (line in VisionCopy.COLOUR_TABLE.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val (name, hex) = line.split('|')
            assertEquals(name, ColourNamer.name(0xFF000000.toInt() or hex.toInt(16)).name)
        }
    }

    @Test
    fun `a washed out colour seems, a saturated or neutral one does not`() {
        val pale = argb(168, 176, 196)
        assertTrue(ColourNamer.name(pale).seems)
        assertEquals("Seems ${ColourNamer.name(pale).name}", ColourNamer.label(pale))
        assertFalse(ColourNamer.name(argb(255, 0, 0)).seems)
        assertFalse(ColourNamer.name(argb(128, 128, 128)).seems)
        assertFalse(ColourNamer.name(argb(250, 250, 250)).seems)
        assertEquals("red", ColourNamer.label(argb(255, 0, 0)))
    }

    @Test
    fun `naming is a pure function`() {
        val c = argb(12, 200, 99)
        assertEquals(ColourNamer.name(c).name, ColourNamer.name(c).name)
    }

    @Test
    fun `no name or table entry mentions a brand or a standard`() {
        val banned = listOf("pantone", "ral", "ncs", "xkcd")
        for (n in ColourNamer.names) for (b in banned) assertFalse("$n has $b", n.split(' ').contains(b))
    }
}
