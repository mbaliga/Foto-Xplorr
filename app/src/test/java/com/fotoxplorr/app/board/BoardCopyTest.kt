package com.fotoxplorr.app.board

import org.junit.Assert.assertEquals
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
}
