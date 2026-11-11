package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.ExportOptions
import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.MaxEdgePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRenderPlanTest {

    @Test
    fun `a size has the asked long edge and the canvas shape`() {
        val s = BoardRenderPlan.sizeForEdge(CanvasSize(1080, 1350), 540)
        assertEquals(432, s.width)
        assertEquals(540, s.height)
        assertEquals(0.4f, s.pxPerUnit, 1e-4f)
        val wide = BoardRenderPlan.sizeForEdge(CanvasSize(1920, 1080), 960)
        assertEquals(960, wide.width)
        assertEquals(540, wide.height)
    }

    @Test
    fun `a size never has more pixels than the editor allows`() {
        val s = BoardRenderPlan.sizeForEdge(CanvasSize(4096, 4096), MAX_EXPORT_EDGE)
        assertTrue(s.width.toLong() * s.height <= MAX_EXPORT_PIXELS)
        assertTrue(s.width in 7900..8000)
        assertEquals(s.width, s.height)
    }

    @Test
    fun `a nonsense edge is one pixel, not a crash`() {
        val s = BoardRenderPlan.sizeForEdge(CanvasSize(1000, 500), 0)
        assertTrue(s.width >= 1 && s.height >= 1)
    }

    @Test
    fun `a board with no photo is exported at its own unit size`() {
        assertEquals(1350, BoardRenderPlan.nativeEdge(board(layer(1, 500f, 500f), canvas = CanvasSize(1080, 1350))))
        assertEquals(1350, BoardRenderPlan.nativeEdge(Board(canvas = CanvasSize(1080, 1350))))
    }

    @Test
    fun `the native edge shows the sharpest photo one photo pixel to one output pixel`() {
        // A 4000 px photo in a 1000 unit box is 4 pixels per unit: the 1080 canvas wants 4320.
        val b = board(layer(1, 540f, 540f, w = 1000f, h = 750f, content = photo(1, 4000, 3000)))
        assertEquals(4320, BoardRenderPlan.nativeEdge(b))
        // A second, sharper one decides.
        val two = board(
            layer(1, 540f, 540f, w = 1000f, h = 750f, content = photo(1, 4000, 3000)),
            layer(2, 540f, 540f, w = 400f, h = 300f, content = photo(2, 4000, 3000)),
        )
        assertEquals(10800.coerceAtMost(MAX_EXPORT_EDGE), BoardRenderPlan.nativeEdge(two))
    }

    @Test
    fun `a crop and a scale are counted, a photo of unknown size or off the canvas is not`() {
        // Half the photo fills a 500 wide box: 2000 photo pixels over 500 units, not 4000.
        val cropped = board(layer(1, 540f, 540f, w = 500f, h = 750f, content = photo(1, 4000, 3000)).copy(crop = CropRect(0f, 0f, 0.5f, 1f)))
        assertEquals(4320, BoardRenderPlan.nativeEdge(cropped))
        val scaled = board(layer(1, 540f, 540f, w = 500f, h = 375f, scale = 2f, content = photo(1, 4000, 3000)))
        assertEquals(4320, BoardRenderPlan.nativeEdge(scaled))
        assertEquals(1080, BoardRenderPlan.nativeEdge(board(layer(1, 540f, 540f, content = photo(1, 0, 0)))))
        assertEquals(1080, BoardRenderPlan.nativeEdge(board(layer(1, 5000f, 5000f, w = 100f, h = 75f, content = photo(1, 4000, 3000)))))
    }

    @Test
    fun `a board of small photos is never exported smaller than its canvas`() {
        val b = board(layer(1, 540f, 540f, w = 1000f, h = 750f, content = photo(1, 500, 375)))
        assertEquals(1080, BoardRenderPlan.nativeEdge(b))
    }

    @Test
    fun `the native edge stops at the editor ceiling`() {
        val b = board(layer(1, 540f, 540f, w = 10f, h = 10f, content = photo(1, 8000, 8000)))
        assertEquals(MAX_EXPORT_EDGE, BoardRenderPlan.nativeEdge(b))
    }

    @Test
    fun `the export edge is held to the person's own cap and the ladder steps down from it`() {
        val b = board(layer(1, 540f, 540f, w = 1000f, h = 750f, content = photo(1, 4000, 3000)))
        assertEquals(4320, BoardRenderPlan.exportEdge(b, ExportOptions()))
        assertEquals(2048, BoardRenderPlan.exportEdge(b, ExportOptions(maxEdge = MaxEdgePreset.MEDIUM)))
        assertEquals(1024, BoardRenderPlan.exportEdge(b, ExportOptions(maxEdge = MaxEdgePreset.SMALL)))
        assertEquals(listOf(4320, 2160, 1536), BoardRenderPlan.exportLadder(b, ExportOptions()))
        assertEquals(listOf(1024), BoardRenderPlan.exportLadder(b, ExportOptions(maxEdge = MaxEdgePreset.SMALL)))
    }

    @Test
    fun `a photo is decoded at the size that shows it sharp and no larger`() {
        val l = layer(1, 0f, 0f, w = 600f, h = 450f, content = photo(1, 4000, 3000))
        // Half a pixel per unit: the box is 300 px wide on the output.
        val a = BoardRenderPlan.decodeLimits(l, 0.5f)!!
        assertEquals(300, a.maxLongEdge)
        assertTrue(a.maxPixels in 300L * 225..(301L * 226))
        // Only half the photo shows: it needs twice the size.
        assertEquals(600, BoardRenderPlan.decodeLimits(l.copy(crop = CropRect(0f, 0f, 0.5f, 1f)), 0.5f)!!.maxLongEdge)
    }

    @Test
    fun `a photo is never asked for above its own size or the ceiling`() {
        val l = layer(1, 0f, 0f, w = 600f, h = 450f, content = photo(1, 4000, 3000))
        assertEquals(4000, BoardRenderPlan.decodeLimits(l, 20f)!!.maxLongEdge)
        val huge = layer(1, 0f, 0f, w = 600f, h = 450f, content = photo(1, 20_000, 15_000))
        val d = BoardRenderPlan.decodeLimits(huge, 40f)!!
        assertEquals(MAX_EXPORT_EDGE, d.maxLongEdge)
        assertTrue(d.maxPixels <= MAX_EXPORT_PIXELS)
    }

    @Test
    fun `a photo of unknown size is sized from the drawn size alone, and other layers are not decoded`() {
        val l = layer(1, 0f, 0f, w = 600f, h = 450f, content = photo(1, 0, 0))
        assertEquals(300, BoardRenderPlan.decodeLimits(l, 0.5f)!!.maxLongEdge)
        assertNull(BoardRenderPlan.decodeLimits(layer(2, 0f, 0f), 1f))
        assertEquals(8, BoardRenderPlan.decodeLimits(l, 0.0001f)!!.maxLongEdge)
    }

    @Test
    fun `a layer's own bitmap is held to its budget with its shape kept`() {
        assertEquals(GroupSize(300, 200), BoardRenderPlan.groupSize(300f, 200f))
        val big = BoardRenderPlan.groupSize(20_000f, 10_000f)
        assertTrue(big.width.toLong() * big.height <= BoardRenderPlan.MAX_GROUP_PIXELS)
        assertEquals(2.0, big.width.toDouble() / big.height, 0.01)
        assertEquals(GroupSize(1, 1), BoardRenderPlan.groupSize(0.2f, Float.NaN))
    }
}
