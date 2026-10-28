package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PaletteCodecTest {

    private val sunset = Palette.of("Sunset", listOf(0xFFFF8800.toInt(), 0xFF00AAFF.toInt(), 0xFF1A1A1A.toInt()))!!
        .withLock(1, true)

    @Test
    fun `the text is exactly this`() {
        assertEquals("fotoz-palette 1\nname Sunset\ncolor #FF8800\ncolor #00AAFF locked\ncolor #1A1A1A\n", PaletteCodec.encode(sunset))
    }

    @Test
    fun `a palette round trips with its name, order and locks`() {
        assertEquals(sunset, PaletteCodec.decode(PaletteCodec.encode(sunset)))
    }

    @Test
    fun `every size round trips`() {
        for (n in 2..12) {
            val p = Palette.of("N$n", List(n) { ColorMath.rgb(it * 21, 255 - it * 9, it * 5) })!!.withLock(n - 1, true)
            assertEquals(p, PaletteCodec.decode(PaletteCodec.encode(p)))
        }
    }

    @Test
    fun `an odd name round trips as the clean name`() {
        val p = sunset.renamed("  two\nlines  and\ttabs ")
        assertEquals("two lines and tabs", PaletteCodec.decode(PaletteCodec.encode(p))!!.name)
    }

    @Test
    fun `windows line ends and blank lines are fine`() {
        val text = "\r\nfotoz-palette 1\r\nname Sunset\r\n\r\ncolor #FF8800\r\ncolor #00AAFF locked\r\ncolor #1A1A1A\r\n"
        assertEquals(sunset, PaletteCodec.decode(text))
    }

    @Test
    fun `a newer version is read for what this build knows, unknown lines ignored`() {
        val text = "fotoz-palette 9\nname Sunset\nmood calm\ncolor #FF8800 shiny\ncolor #00AAFF locked extra\ncolor #1A1A1A\nfooter\n"
        val p = PaletteCodec.decode(text)
        assertNotNull(p)
        assertEquals(sunset.argbs, p!!.argbs)
        assertEquals(listOf(false, true, false), p.colors.map { it.locked })
    }

    @Test
    fun `a missing name takes the default and a second name line is ignored`() {
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, PaletteCodec.decode("fotoz-palette 1\ncolor #000000\ncolor #FFFFFF\n")!!.name)
        assertEquals("First", PaletteCodec.decode("fotoz-palette 1\nname First\nname Second\ncolor #000000\ncolor #FFFFFF\n")!!.name)
    }

    @Test
    fun `corrupt text is null, never an exception`() {
        val bad = listOf(
            "",
            "   \n\n",
            "garbage",
            "fotoz-palette",
            "fotoz-palette x\ncolor #000000\ncolor #FFFFFF\n",
            "fotoz-palette 0\ncolor #000000\ncolor #FFFFFF\n",
            "fotoz-palette -3\ncolor #000000\ncolor #FFFFFF\n",
            "fotoz-palette 1 extra\ncolor #000000\ncolor #FFFFFF\n",
            "palette 1\ncolor #000000\ncolor #FFFFFF\n",
            "name Sunset\ncolor #000000\ncolor #FFFFFF\n",
            "fotoz-palette 1\nname Only\n",
            "fotoz-palette 1\ncolor #000000\n",
            "fotoz-palette 1\ncolor #000000\ncolor #GGGGGG\n",
            "fotoz-palette 1\ncolor #000000\ncolor #FFF\n",
            "fotoz-palette 1\ncolor #000000\ncolor FFFFFF\n",
            "fotoz-palette 1\ncolor #000000\ncolor #FFFFFF80\n",
            "fotoz-palette 1\n" + "color #000000\n".repeat(13),
            "\u0000\u0001\u0002",
            "{\"name\":\"json is not this format\"}",
        )
        bad.forEach { assertNull(it, PaletteCodec.decode(it)) }
    }

    @Test
    fun `text over the size limit is refused`() {
        val huge = "fotoz-palette 1\ncolor #000000\ncolor #FFFFFF\n" + "# padding\n".repeat(600)
        assertTrue(huge.length > PaletteCodec.MAX_TEXT_LENGTH)
        assertNull(PaletteCodec.decode(huge))
    }

    @Test
    fun `random garbage and damaged real text never throw`() {
        val rnd = Random(2024L)
        val good = PaletteCodec.encode(sunset)
        repeat(500) {
            val chars = CharArray(rnd.nextInt(80)) { (rnd.nextInt(96) + 32).toChar() }
            PaletteCodec.decode(String(chars))
            val cut = good.substring(0, rnd.nextInt(good.length))
            PaletteCodec.decode(cut)
            val flipped = good.toCharArray().also { it[rnd.nextInt(it.size)] = (rnd.nextInt(96) + 32).toChar() }
            PaletteCodec.decode(String(flipped))
        }
    }

    @Test
    fun `several palettes round trip in order`() {
        val other = Palette.of("Other", listOf(0xFF010203.toInt(), 0xFF040506.toInt()))!!
        val all = listOf(sunset, other, sunset.renamed("Third"))
        assertEquals(all, PaletteCodec.decodeAll(PaletteCodec.encodeAll(all)))
    }

    @Test
    fun `a damaged palette among good ones is skipped and the rest are kept`() {
        val other = Palette.of("Other", listOf(0xFF010203.toInt(), 0xFF040506.toInt()))!!
        val text = PaletteCodec.encode(sunset) + "---\n" + "fotoz-palette 1\ncolor #zzzzzz\ncolor #000000\n" + "---\n" + PaletteCodec.encode(other)
        assertEquals(listOf(sunset, other), PaletteCodec.decodeAll(text))
    }

    @Test
    fun `nothing saved reads as an empty list`() {
        assertEquals(emptyList<Palette>(), PaletteCodec.decodeAll(""))
        assertEquals(emptyList<Palette>(), PaletteCodec.decodeAll("\n\n"))
        assertEquals("", PaletteCodec.encodeAll(emptyList()))
    }

    @Test
    fun `no more than the saved limit is read`() {
        val many = List(PaletteCodec.MAX_SAVED + 20) { sunset.renamed("P$it") }
        assertEquals(PaletteCodec.MAX_SAVED, PaletteCodec.decodeAll(PaletteCodec.encodeAll(many)).size)
    }
}
