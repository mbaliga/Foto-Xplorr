package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asInt
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PaletteExportTest {

    private val sunset = Palette.of("Sunset", listOf(0xFFFF8800.toInt(), 0xFF00AAFF.toInt(), 0xFF1A1A1A.toInt()))!!

    @Test
    fun `the gpl file is exactly this text`() {
        val expected = "GIMP Palette\n" +
            "Name: Sunset\n" +
            "Columns: 3\n" +
            "#\n" +
            "255 136   0\t#FF8800\n" +
            "  0 170 255\t#00AAFF\n" +
            " 26  26  26\t#1A1A1A\n"
        assertEquals(expected, PaletteExport.gpl(sunset))
    }

    @Test
    fun `the exports are the same text whatever digits the phone's language uses`() {
        val before = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "bn-BD", "my-MM", "hi-IN")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "255 136   0\t#FF8800", PaletteExport.gpl(sunset).lines()[4])
                assertEquals(tag, "  0 170 255\t#00AAFF", PaletteExport.gpl(sunset).lines()[5])
                assertEquals(tag, "#FF8800", sunset.colors[0].hex)
                assertTrue(tag, PaletteExport.css(sunset).contains("--sunset-3: #1A1A1A;"))
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `the gpl column hint stops at six`() {
        val twelve = Palette.of("Big", List(12) { ColorMath.rgb(it * 20, 0, 0) })!!
        assertEquals("Columns: 6", PaletteExport.gpl(twelve).lines()[2])
        assertEquals(12, PaletteExport.gpl(twelve).lines().count { it.contains('\t') })
    }

    @Test
    fun `the css is exactly this text`() {
        val expected = ":root {\n" +
            "  --sunset-1: #FF8800;\n" +
            "  --sunset-2: #00AAFF;\n" +
            "  --sunset-3: #1A1A1A;\n" +
            "}\n"
        assertEquals(expected, PaletteExport.css(sunset))
    }

    @Test
    fun `the css name is a safe slug even for an odd palette name`() {
        val odd = sunset.renamed("  Crème brûlée & <b>co</b>!  ")
        assertEquals("cr-me-br-l-e-b-co-b", PaletteExport.slug(odd.name))
        assertEquals("palette", PaletteExport.slug("日本語"))
        assertEquals("--palette-1", PaletteExport.css(sunset.renamed("日本語")).lines()[1].trim().substringBefore(':'))
    }

    @Test
    fun `the json is exactly this text`() {
        val expected = "{\"name\":\"Sunset\",\"colors\":[" +
            "{\"hex\":\"#FF8800\",\"rgb\":[255,136,0]}," +
            "{\"hex\":\"#00AAFF\",\"rgb\":[0,170,255]}," +
            "{\"hex\":\"#1A1A1A\",\"rgb\":[26,26,26]}]}"
        assertEquals(expected, PaletteExport.json(sunset))
    }

    @Test
    fun `the json stays valid for a name with quotes and a backslash`() {
        val tricky = sunset.renamed("Say \"hi\" \\ there")
        val root = parseJson(PaletteExport.json(tricky))
        assertNotNull(root)
        assertEquals("Say \"hi\" \\ there", root!!["name"].asString())
        assertEquals(3, root["colors"].asArray().size)
        assertEquals(255, root["colors"].asArray()[0]["rgb"].asArray()[0].asInt())
    }

    @Test
    fun `locks are not exported`() {
        val locked = sunset.withLock(0, true)
        assertEquals(PaletteExport.gpl(sunset), PaletteExport.gpl(locked))
        assertEquals(PaletteExport.css(sunset), PaletteExport.css(locked))
        assertEquals(PaletteExport.json(sunset), PaletteExport.json(locked))
    }

    @Test
    fun `text is given for the text formats and not for the card`() {
        assertEquals(PaletteExport.gpl(sunset), PaletteExport.text(sunset, ExportFormat.GPL))
        assertEquals(PaletteExport.css(sunset), PaletteExport.text(sunset, ExportFormat.CSS))
        assertEquals(PaletteExport.json(sunset), PaletteExport.text(sunset, ExportFormat.JSON))
        assertNull(PaletteExport.text(sunset, ExportFormat.CARD))
    }

    @Test
    fun `file names are the slug and the extension`() {
        assertEquals("sunset.gpl", PaletteExport.fileName(sunset, ExportFormat.GPL))
        assertEquals("sunset.css", PaletteExport.fileName(sunset, ExportFormat.CSS))
        assertEquals("sunset.json", PaletteExport.fileName(sunset, ExportFormat.JSON))
        assertEquals("sunset.png", PaletteExport.fileName(sunset, ExportFormat.CARD))
    }
}
