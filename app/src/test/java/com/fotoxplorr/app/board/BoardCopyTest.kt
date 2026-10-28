package com.fotoxplorr.app.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCopyTest {

    @Test
    fun `every canvas, blend and shape has its own short name`() {
        val canvases = CanvasPreset.entries.map(BoardCopy::canvas)
        assertEquals(canvases.size, canvases.toSet().size)
        val blends = LayerBlend.entries.map(BoardCopy::blend)
        assertEquals(blends.size, blends.toSet().size)
        val shapes = ShapeKind.entries.map(BoardCopy::shape)
        assertEquals(shapes.size, shapes.toSet().size)
        assertTrue((canvases + blends + shapes).all { it.isNotBlank() && it.length <= 20 })
    }

    @Test
    fun `masks are named by what they do, and a feathered whole box is a soft edge`() {
        assertEquals("No mask", BoardCopy.mask(LayerMask.NONE))
        assertEquals("Soft edge", BoardCopy.mask(LayerMask(MaskShape.Full, 0.2f)))
        assertEquals("Oval", BoardCopy.mask(LayerMask(MaskShape.Ellipse)))
        assertEquals("Subject", BoardCopy.mask(LayerMask(MaskShape.Subject(emptyList()))))
    }

    @Test
    fun `a layer is named by its kind and its place from the back`() {
        assertEquals("Photo 2", BoardCopy.layerName(BoardTestKit.layer(1, 0f, 0f, content = BoardTestKit.photo(1)), 2))
        assertEquals("Text 1, Hello", BoardCopy.layerName(BoardTestKit.layer(1, 0f, 0f, content = LayerContent.Text("Hello")), 1))
        assertEquals("Oval 3", BoardCopy.layerName(BoardTestKit.layer(1, 0f, 0f, content = LayerContent.Shape(ShapeKind.ELLIPSE)), 3))
    }

    // ---- the screen's words ----

    private val sentences: List<Pair<String, String>> = BoardCopy::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.isAccessible = true; it.name to it.get(null) as String }

    @Test
    fun `every word the board shows is short, trimmed and not empty`() {
        assertTrue("found ${sentences.size} strings", sentences.size > 100)
        for ((name, text) in sentences) {
            assertTrue("$name is empty", text.isNotBlank())
            assertEquals("$name has spaces at an end", text.trim(), text)
            assertTrue("$name is long (${text.length}): $text", text.length <= 90)
        }
    }

    @Test
    fun `a sentence is one sentence and a label has no full stop`() {
        for ((name, text) in sentences) {
            val inside = text.dropLast(1).contains(". ")
            assertFalse("$name is more than one sentence: $text", inside && !name.startsWith("COLOUR_"))
        }
    }

    @Test
    fun `nothing the board says names a network, an account or audio`() {
        val words = listOf("internet", "online", "cloud", "account", "sign in", "audio", "sound", "music")
        for ((name, text) in sentences) {
            assertTrue("$name mentions ${words.firstOrNull { text.contains(it, ignoreCase = true) }}", words.none { text.contains(it, ignoreCase = true) })
        }
    }

    @Test
    fun `every export problem has its own sentence`() {
        val texts = BoardExportProblem.entries.map(BoardCopy::problem)
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() && it.endsWith(".") })
    }

    @Test
    fun `every mask option and colour has a name, and colour names are unique`() {
        assertEquals(MaskKind.entries.size, MaskKind.entries.map(BoardCopy::maskOption).toSet().size)
        assertEquals(BoardColours.BASICS.size, BoardColours.BASICS.map { it.name }.toSet().size)
        assertEquals(BoardColours.BASICS.size, BoardColours.BASICS.map { it.argb }.toSet().size)
        assertTrue(BoardColours.BASICS.all { (it.argb ushr 24) == 255 })
    }

    @Test
    fun `figures go into whole sentences`() {
        assertEquals("Size 121%", BoardCopy.sizeNow(1.21f))
        assertEquals("Turn -15°", BoardCopy.turnNow(-15f))
        assertEquals("Opacity 65%", BoardCopy.opacityNow(0.65f))
        assertEquals("Soft edge 10%", BoardCopy.featherNow(0.1f))
        assertEquals("1 point", BoardCopy.pointsNow(1))
        assertEquals("3 points", BoardCopy.pointsNow(3))
        assertEquals("1 mark", BoardCopy.marksNow(1))
        assertEquals("Add 1 photo", BoardCopy.addPhotos(1))
        assertEquals("Add 4 photos", BoardCopy.addPhotos(4))
        assertEquals("1 layer, Square", BoardCopy.boardSummary(1, "Square"))
        assertEquals("Board, 2 layers. Photo 2 picked.", BoardCopy.canvasDescription(2, "Photo 2"))
        assertEquals("#336699, 40% of the photos", BoardCopy.swatch("#336699", 0.4f))
        assertEquals("#336699", BoardCopy.swatch("#336699", null))
        assertEquals("About 1024 by 768 pixels.", BoardCopy.aboutSize(1024, 768))
        assertEquals("Saved smaller than asked, 1536 by 1024, to fit in memory.", BoardCopy.sizeNote(1536, 1024, true))
    }

    @Test
    fun `black is read on a light colour and white on a dark one`() {
        assertTrue(isLight(0xFFFFFFFF.toInt()))
        assertTrue(isLight(0xFFFBC02D.toInt()))
        assertFalse(isLight(0xFF000000.toInt()))
        assertFalse(isLight(0xFF7B1FA2.toInt()))
    }

    @Test
    fun `a canvas is named by its preset or by its size`() {
        assertEquals("Square", canvasName(CanvasPreset.SQUARE.size))
        assertEquals("1000 by 700", canvasName(CanvasSize(1000, 700)))
    }
}
