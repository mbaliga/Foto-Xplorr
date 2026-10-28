package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardOpsTest {

    private val tol = 0.01f
    private val ref = PhotoRef(7, null, 4000, 3000)

    @Test
    fun `a photo is added centred, fitted inside sixty percent of the canvas, with its own shape`() {
        val added = BoardOps.addPhoto(Board(canvas = CanvasSize(1000, 1000)), ref)
        val l = added.board.layer(added.layerId!!)!!
        assertEquals(600f, l.width, tol)
        assertEquals(450f, l.height, tol)
        assertEquals(500f, l.transform.cx, tol)
        assertEquals(500f, l.transform.cy, tol)
        val tall = BoardOps.addPhoto(Board(canvas = CanvasSize(1000, 1000)), PhotoRef(8, null, 3000, 4000))
        val t = tall.board.layers.single()
        assertEquals(450f, t.width, tol)
        assertEquals(600f, t.height, tol)
    }

    @Test
    fun `a photo of unknown size is added square`() {
        val l = BoardOps.addPhoto(Board(canvas = CanvasSize(1000, 1000)), PhotoRef(8)).board.layers.single()
        assertEquals(l.width, l.height, tol)
    }

    @Test
    fun `layers get increasing ids that are never given out twice, even after a delete`() {
        var b = Board()
        val a = BoardOps.addColour(b, BoardTestKit.RED)
        val c = BoardOps.addColour(a.board, BoardTestKit.BLUE)
        assertEquals(listOf(1L, 2L), c.board.layers.map { it.id })
        b = BoardOps.remove(c.board, 2)
        val d = BoardOps.addColour(b, BoardTestKit.GREEN)
        assertEquals(3L, d.layerId)
    }

    @Test
    fun `each new layer sits a little down and right of the one before`() {
        var b = Board(canvas = CanvasSize(1000, 1000))
        repeat(3) { b = BoardOps.addColour(b, BoardTestKit.RED).board }
        val xs = b.layers.map { it.transform.cx }
        assertTrue(xs[0] < xs[1] && xs[1] < xs[2])
    }

    @Test
    fun `a full board takes no more layers`() {
        var b = Board()
        repeat(BoardLimits.MAX_LAYERS) { b = BoardOps.addShape(b, ShapeKind.ELLIPSE, 0).board }
        assertEquals(BoardLimits.MAX_LAYERS, b.layers.size)
        val more = BoardOps.addText(b, "hi")
        assertNull(more.layerId)
        assertSame(b, more.board)
        assertNull(BoardOps.duplicate(b, 1).layerId)
    }

    @Test
    fun `an edit that changes nothing returns the same board`() {
        val b = board(layer(1, 100f, 100f, opacity = 0.5f))
        assertSame(b, BoardOps.setOpacity(b, 1, 0.5f))
        assertSame(b, BoardOps.setBlend(b, 1, LayerBlend.NORMAL))
        assertSame(b, BoardOps.moveBy(b, 1, 0f, 0f))
        assertSame(b, BoardOps.setLocked(b, 1, false))
        assertSame(b, BoardOps.setMask(b, 1, LayerMask.NONE))
        assertSame(b, BoardOps.setOpacity(b, 99, 0.2f))
        assertSame(b, BoardOps.remove(b, 99))
        assertSame(b, BoardOps.setBackground(b, b.background))
        assertSame(b, BoardOps.setCanvas(b, b.canvas))
    }

    @Test
    fun `a locked layer takes no edit until it is unlocked`() {
        val b = board(layer(1, 100f, 100f, locked = true), layer(2, 300f, 300f))
        assertSame(b, BoardOps.moveBy(b, 1, 10f, 10f))
        assertSame(b, BoardOps.setTransform(b, 1, Transform(5f, 5f)))
        assertSame(b, BoardOps.setOpacity(b, 1, 0.1f))
        assertSame(b, BoardOps.setBlend(b, 1, LayerBlend.MULTIPLY))
        assertSame(b, BoardOps.setMask(b, 1, LayerMask(MaskShape.Ellipse)))
        assertSame(b, BoardOps.setContent(b, 1, LayerContent.Colour(0)))
        assertSame(b, BoardOps.setBox(b, 1, 10f, 10f))
        assertSame(b, BoardOps.remove(b, 1))
        assertSame(b, BoardOps.restack(b, 1, StackMove.TO_FRONT))
        val open = BoardOps.setLocked(b, 1, false)
        assertFalse(open.layer(1)!!.locked)
        assertEquals(110f, BoardOps.moveBy(open, 1, 10f, 0f).layer(1)!!.transform.cx, tol)
        // Locking is itself an edit that sticks.
        assertTrue(BoardOps.setLocked(open, 2, true).layer(2)!!.locked)
    }

    @Test
    fun `a locked layer can still be duplicated and the copy is not locked`() {
        val b = board(layer(1, 100f, 100f, locked = true))
        val d = BoardOps.duplicate(b, 1)
        val copy = d.board.layer(d.layerId!!)!!
        assertFalse(copy.locked)
        assertEquals(124f, copy.transform.cx, tol)
        assertEquals(listOf(1L, d.layerId), d.board.layers.map { it.id })
    }

    @Test
    fun `a duplicate sits just above its original`() {
        val b = board(layer(1, 100f, 100f), layer(2, 200f, 200f), layer(3, 300f, 300f))
        val d = BoardOps.duplicate(b, 1)
        assertEquals(listOf(1L, 4L, 2L, 3L), d.board.layers.map { it.id })
    }

    @Test
    fun `opacity is held to zero to one and a nonsense value is zero`() {
        val b = board(layer(1, 100f, 100f))
        assertEquals(1f, BoardOps.setOpacity(b, 1, 3f).layer(1)!!.opacity, 0f)
        assertEquals(0f, BoardOps.setOpacity(b, 1, -3f).layer(1)!!.opacity, 0f)
        assertEquals(0f, BoardOps.setOpacity(b, 1, Float.NaN).layer(1)!!.opacity, 0f)
    }

    @Test
    fun `a transform is held in range and the turn is folded`() {
        val b = board(layer(1, 100f, 100f))
        val t = BoardOps.setTransform(b, 1, Transform(Float.NaN, 1e12f, 0f, 270f)).layer(1)!!.transform
        assertEquals(540f, t.cx, tol)
        assertEquals(1080f * 5f, t.cy, tol)
        assertEquals(BoardLimits.MIN_SCALE, t.scale, 0f)
        assertEquals(-90f, t.rotationDeg, tol)
        assertEquals(BoardLimits.MAX_SCALE, BoardOps.setTransform(b, 1, Transform(0f, 0f, 1e9f)).layer(1)!!.transform.scale, 0f)
    }

    @Test
    fun `stacking moves a layer one step or to an end and stops at the ends`() {
        val b = board(layer(1, 0f, 0f), layer(2, 0f, 0f), layer(3, 0f, 0f))
        fun order(x: Board) = x.layers.map { it.id }
        assertEquals(listOf(2L, 1L, 3L), order(BoardOps.restack(b, 1, StackMove.FORWARD)))
        assertEquals(listOf(1L, 3L, 2L), order(BoardOps.restack(b, 3, StackMove.BACKWARD)))
        assertEquals(listOf(2L, 3L, 1L), order(BoardOps.restack(b, 1, StackMove.TO_FRONT)))
        assertEquals(listOf(3L, 1L, 2L), order(BoardOps.restack(b, 3, StackMove.TO_BACK)))
        assertSame(b, BoardOps.restack(b, 3, StackMove.FORWARD))
        assertSame(b, BoardOps.restack(b, 1, StackMove.BACKWARD))
        assertSame(b, BoardOps.restack(b, 3, StackMove.TO_FRONT))
    }

    @Test
    fun `cropping a photo keeps its width and takes the shape of the crop, so it is never stretched`() {
        val b = BoardOps.addPhoto(Board(canvas = CanvasSize(1000, 1000)), ref).board
        val id = b.layers.single().id
        val half = BoardOps.setCrop(b, id, CropRect(0f, 0f, 0.5f, 1f)).layer(id)!!
        assertEquals(600f, half.width, tol)
        // 4:3 photo, left half kept: 2:3 wide-to-tall, so a 600 wide box is 900 tall.
        assertEquals(900f, half.height, tol)
        assertEquals(CropRect(0f, 0f, 0.5f, 1f), half.crop)
        val back = BoardOps.setCrop(half.let { b.copy(layers = listOf(it)) }, id, CropRect.FULL).layer(id)!!
        assertEquals(450f, back.height, tol)
    }

    @Test
    fun `a crop on a layer that is not a photo changes nothing`() {
        val b = board(layer(1, 100f, 100f))
        assertSame(b, BoardOps.setCrop(b, 1, CropRect(0f, 0f, 0.5f, 0.5f)))
    }

    @Test
    fun `changing a text layer's words changes its height and cuts a long text`() {
        val added = BoardOps.addText(Board(canvas = CanvasSize(1000, 1000)), "one")
        val id = added.layerId!!
        val oneLine = added.board.layer(id)!!.height
        val many = BoardOps.setContent(added.board, id, LayerContent.Text("word ".repeat(100))).layer(id)!!
        assertTrue(many.height > oneLine)
        val huge = BoardOps.setContent(added.board, id, LayerContent.Text("x".repeat(5000))).layer(id)!!
        assertEquals(BoardLimits.MAX_TEXT_CHARS, (huge.content as LayerContent.Text).text.length)
    }

    @Test
    fun `text height is the lines times the font size times the line gap`() {
        val c = LayerContent.Text("abcdefghij", sizeUnits = 10f)
        // 60 units hold 10 characters of 5.5 units each.
        assertEquals(12f, TextBox.height(c, 60f), tol)
        assertEquals(24f, TextBox.height(LayerContent.Text("abcdefghijk", sizeUnits = 10f), 60f), tol)
        assertEquals(24f, TextBox.height(LayerContent.Text("a\nb", sizeUnits = 10f), 60f), tol)
    }

    @Test
    fun `a mask is stored with its numbers inside what the geometry understands`() {
        val b = board(layer(1, 100f, 100f, content = photo(1)))
        val m = BoardOps.setMask(
            b, 1,
            LayerMask(MaskShape.Polygon(List(100) { NormPoint(2f, -1f) }), feather = 9f),
        ).layer(1)!!.mask
        val poly = m.shape as MaskShape.Polygon
        assertEquals(BoardLimits.MAX_POLYGON_POINTS, poly.points.size)
        assertEquals(NormPoint(1f, 0f), poly.points[0])
        assertEquals(0.5f, m.feather, 0f)
        val round = BoardOps.setMask(b, 1, LayerMask(MaskShape.RoundedRect(4f))).layer(1)!!.mask.shape as MaskShape.RoundedRect
        assertEquals(0.5f, round.radius, 0f)
    }

    @Test
    fun `a subject mask takes no feather`() {
        val b = board(layer(1, 100f, 100f, content = photo(1)))
        val m = BoardOps.setMask(b, 1, LayerMask(MaskShape.Subject(listOf(BoardTestKit.hint(true, 0.5f, 0.5f))), 0.3f)).layer(1)!!.mask
        assertEquals(0f, m.feather, 0f)
    }

    @Test
    fun `the canvas and the background change as one edit each, and a canvas is held to the limits`() {
        val b = board(layer(1, 100f, 100f))
        val wide = BoardOps.setCanvas(b, CanvasSize(100_000, 3))
        assertEquals(BoardLimits.MAX_CANVAS_EDGE, wide.canvas.width)
        assertEquals(BoardLimits.MIN_CANVAS_EDGE, wide.canvas.height)
        assertEquals(b.layers, wide.layers)
        val clear = BoardOps.setBackground(b, BoardBackground.Transparent)
        assertNotSame(b, clear)
        assertTrue(clear.needsAlpha)
        assertFalse(b.needsAlpha)
        assertTrue(b.copy(background = BoardBackground.Colour(0x80FFFFFF.toInt())).needsAlpha)
    }

    @Test
    fun `the photos a board shows are listed once each`() {
        val b = board(
            layer(1, 0f, 0f, content = photo(5)), layer(2, 0f, 0f, content = photo(5)),
            layer(3, 0f, 0f, content = photo(6)), layer(4, 0f, 0f),
        )
        assertEquals(listOf(5L, 6L), b.photoRefs.map { it.mediaId })
        assertNotNull(b.layer(4))
    }
}
