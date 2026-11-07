package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourNamesTest {

    private fun top(r: Int, g: Int, b: Int) = ColourNames.nearest(ChromaTestKit.argb(r, g, b), 1).single().name

    @Test
    fun theTableHasAboutThreeHundredAndFortyNamesAllDistinct() {
        assertTrue("size ${ColourNames.size}", ColourNames.size in 300..400)
        assertEquals(ColourNames.size, ColourNames.names.toSet().size)
        assertTrue(ColourTable.OWN.lines().size >= 200)
    }

    @Test
    fun noTwoNamesShareAColour() {
        val hexes = (ColourTable.CSS.lines() + ColourTable.OWN.lines()).map { it.substringAfter('|').uppercase() }
        assertEquals(hexes.size, hexes.toSet().size)
    }

    @Test
    fun noTrademarkedColourSystemAppearsInTheNames() {
        val banned = setOf("pantone", "ral", "ncs", "pms", "munsell", "dulux", "benjamin", "sherwin", "behr", "farrow")
        for (n in ColourNames.names) for (w in n.lowercase().split(' ')) assertFalse("$n has the word $w", w in banned)
        // Nor a code like "RAL 3020" or "PMS 186" or "S 2060-Y": names are words.
        for (n in ColourNames.names) assertTrue("$n", n.all { it.isLetter() || it == ' ' })
        assertFalse(ColourTable.CSS.contains("pantone", true) || ColourTable.OWN.contains("pantone", true))
    }

    @Test
    fun theCanonicalColoursGetTheirCanonicalNames() {
        assertEquals("white", top(255, 255, 255))
        assertEquals("black", top(0, 0, 0))
        assertEquals("red", top(255, 0, 0))
        assertEquals("lime", top(0, 255, 0))
        assertEquals("blue", top(0, 0, 255))
        assertEquals("yellow", top(255, 255, 0))
        assertEquals("cyan", top(0, 255, 255))
        assertEquals("magenta", top(255, 0, 255))
        assertEquals("green", top(0, 128, 0))
        assertEquals("navy", top(0, 0, 128))
        assertEquals("orange", top(255, 165, 0))
        assertEquals("grey", top(128, 128, 128))
        assertEquals("pink", top(255, 192, 203))
        assertEquals("teal", top(0, 128, 128))
    }

    @Test
    fun everyTableColourIsNearestToItself() {
        for (line in ColourTable.CSS.lines() + ColourTable.OWN.lines()) {
            val (name, hex) = line.split('|')
            val m = ColourNames.nearest(ColourSpace.parseHex(hex)!!, 1).single()
            assertEquals(name, m.name)
            assertEquals(0.0, m.deltaE, 1e-9)
        }
    }

    @Test
    fun theNearestThreeComeNearestFirstWithTheirDistances() {
        val m = ColourNames.nearest(ChromaTestKit.argb(200, 60, 50), 3)
        assertEquals(3, m.size)
        assertTrue(m[0].deltaE <= m[1].deltaE && m[1].deltaE <= m[2].deltaE)
        assertEquals(3, m.map { it.name }.toSet().size)
        for (x in m) {
            assertEquals(ColourSpace.hex(x.argb), x.hex)
            assertEquals(DeltaE2000.between(ColourSpace.lab(ChromaTestKit.argb(200, 60, 50)), x.lab), x.deltaE, 1e-9)
            assertEquals((x.argb shr 16) and 0xFF, x.red)
        }
    }

    @Test
    fun aColourBetweenNamesGetsAReasonableNeighbour() {
        // Everyday colours, judged by a person: the nearest name must be in the right family.
        assertTrue(top(30, 60, 120).contains("blue") || top(30, 60, 120) in setOf("denim", "navy", "sapphire", "delft blue", "cobalt", "ocean blue"))
        assertTrue(top(120, 180, 60).let { it.contains("green") || it in setOf("kiwi", "lettuce", "yellow green", "pea green", "celery") })
    }

    @Test
    fun plainWordsForColours() {
        fun d(r: Int, g: Int, b: Int) = ColourWords.describe(ColourSpace.lab(ChromaTestKit.argb(r, g, b)))
        assertEquals("black", d(0, 0, 0))
        assertEquals("white", d(255, 255, 255))
        assertEquals("mid grey", d(128, 128, 128))
        assertEquals("dark grey", d(60, 60, 60))
        assertEquals("light grey", d(200, 200, 200))
        assertTrue(d(255, 0, 0), d(255, 0, 0).endsWith("red"))
        assertTrue(d(255, 0, 0), d(255, 0, 0).contains("vivid") || d(255, 0, 0).contains("strong"))
        assertTrue(d(0, 0, 255), d(0, 0, 255).endsWith("blue"))
        assertTrue(d(0, 128, 0), d(0, 128, 0).endsWith("green"))
        assertTrue(d(255, 255, 0), d(255, 255, 0).endsWith("yellow"))
        assertTrue(d(255, 165, 0), d(255, 165, 0).endsWith("orange"))
        assertTrue(d(128, 0, 128), d(128, 0, 128).endsWith("purple"))
        assertTrue(d(0, 255, 255), d(0, 255, 255).endsWith("blue-green"))
        assertTrue(d(150, 205, 50), d(150, 205, 50).endsWith("yellow-green"))
        assertTrue(d(235, 200, 205), d(235, 200, 205).startsWith("very light") || d(235, 200, 205).startsWith("light"))
        assertTrue(d(130, 120, 110), d(130, 120, 110).contains("greyish") || d(130, 120, 110).contains("muted"))
    }

    @Test
    fun theReportCarriesEveryField() {
        val r = ColourReport.of(ChromaTestKit.argb(200, 60, 50))
        assertEquals("#C83C32", r.hex)
        assertEquals(200, r.red); assertEquals(60, r.green); assertEquals(50, r.blue)
        assertEquals(r.lab.l, r.lch.l, 1e-9)
        assertEquals(r.lab.chroma, r.lch.c, 1e-9)
        assertEquals(3, r.nearest.size)
        assertTrue(r.description.isNotBlank())
    }

    @Test
    fun theHueFamiliesCoverTheWholeCircle() {
        val seen = HashSet<String>()
        var h = 0.0
        while (h < 360.0) { seen += ColourWords.hueFamily(h); h += 1.0 }
        assertEquals(9, seen.size)
        assertEquals(ColourWords.hueFamily(359.0), ColourWords.hueFamily(1.0))
        assertEquals(ColourWords.hueFamily(-30.0), ColourWords.hueFamily(330.0))
    }
}
