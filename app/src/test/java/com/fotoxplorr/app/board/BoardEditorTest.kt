package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's buttons as pure functions: what each does to the board, the pick, and the undo steps. */
class BoardEditorTest {

    private fun empty() = BoardEditor.of(Board())

    private fun withColour(): BoardEditor = BoardEditing.addColour(empty(), RED)

    private val ref = PhotoRef(7, BoardTestKit.identity("a.jpg", 100), 4000, 3000)

    // ---- adding ----

    @Test
    fun `adding a layer picks it, and one undo takes it away`() {
        val e = BoardEditing.addColour(empty(), RED)
        assertEquals(1, e.board.layers.size)
        assertEquals(e.board.layers.single().id, e.selectedId)
        val back = BoardEditing.undo(e)
        assertTrue(back.board.layers.isEmpty())
        assertNull("the layer is gone, so nothing is picked", back.selectedId)
        assertEquals(e.board, BoardEditing.redo(back).board)
    }

    @Test
    fun `several photos are added in order and the last one is picked`() {
        val refs = listOf(ref, ref.copy(mediaId = 8), ref.copy(mediaId = 9))
        val r = BoardEditing.addPhotos(empty(), refs)
        assertEquals(3, r.added)
        assertEquals(listOf(7L, 8L, 9L), r.editor.board.layers.map { (it.content as LayerContent.Photo).ref.mediaId })
        assertEquals(r.editor.board.layers.last().id, r.editor.selectedId)
        assertEquals("all three are one undo step", 0, BoardEditing.undo(r.editor).board.layers.size)
    }

    @Test
    fun `photos stop at the layer limit and say how many fitted`() {
        var e = empty()
        repeat(BoardLimits.MAX_LAYERS - 1) { e = BoardEditing.addColour(e, RED) }
        val r = BoardEditing.addPhotos(e, listOf(ref, ref.copy(mediaId = 8), ref.copy(mediaId = 9)))
        assertEquals(1, r.added)
        assertEquals(BoardLimits.MAX_LAYERS, r.editor.board.layers.size)
        val none = BoardEditing.addPhotos(r.editor, listOf(ref))
        assertEquals(0, none.added)
        assertSame(r.editor, none.editor)
    }

    @Test
    fun `adding text with only blanks does nothing, and a colour is the one asked for`() {
        val e = empty()
        assertSame(e, BoardEditing.addText(e, "   ", RED))
        val t = BoardEditing.addText(e, "Hi", BLUE)
        assertEquals(LayerContent.Text("Hi", BLUE), t.selected!!.content)
        val s = BoardEditing.addShape(e, ShapeKind.TRIANGLE, BLUE)
        assertEquals(ShapeKind.TRIANGLE, (s.selected!!.content as LayerContent.Shape).kind)
    }

    // ---- nothing picked ----

    @Test
    fun `every layer button does nothing when no layer is picked`() {
        val e = BoardEditing.select(withColour(), null)
        assertSame(e, BoardEditing.nudge(e, 10f, 0f))
        assertSame(e, BoardEditing.scaleBy(e, 1.1f))
        assertSame(e, BoardEditing.turnBy(e, 15f))
        assertSame(e, BoardEditing.opacityBy(e, -0.05f))
        assertSame(e, BoardEditing.setBlend(e, LayerBlend.SCREEN))
        assertSame(e, BoardEditing.restack(e, StackMove.FORWARD))
        assertSame(e, BoardEditing.duplicate(e))
        assertSame(e, BoardEditing.delete(e))
        assertSame(e, BoardEditing.centre(e))
        assertSame(e, BoardEditing.setColour(e, BLUE))
    }

    @Test
    fun `selecting an id that is not on the board picks nothing`() {
        val e = withColour()
        assertNull(BoardEditing.select(e, 99L).selectedId)
        assertSame(e, BoardEditing.select(e, e.selectedId))
    }

    // ---- nudge, size, turn, opacity ----

    @Test
    fun `a nudge moves by the step, and a run of nudges on one layer is one undo step`() {
        val e0 = withColour()
        val x0 = e0.selected!!.transform.cx
        var e = e0
        repeat(3) { e = BoardEditing.nudge(e, BoardEditing.Steps.NUDGE_UNITS, 0f) }
        assertEquals(x0 + 30f, e.selected!!.transform.cx, 0.001f)
        assertEquals(x0, BoardEditing.undo(e).selected!!.transform.cx, 0.001f)
        assertEquals("the add is still there", 1, BoardEditing.undo(e).board.layers.size)
    }

    @Test
    fun `nudges on two layers are two undo steps`() {
        var e = BoardEditing.addColour(withColour(), BLUE)
        val second = e.selectedId!!
        e = BoardEditing.nudge(e, 10f, 0f)
        val first = e.board.layers.first().id
        e = BoardEditing.select(e, first)
        e = BoardEditing.nudge(e, 0f, 10f)
        val undone = BoardEditing.undo(e)
        assertEquals("only the second nudge came off", 10f, undone.board.layer(second)!!.transform.cx - BoardEditing.undo(undone).board.layer(second)!!.transform.cx, 0.001f)
    }

    @Test
    fun `a nudge is held to the reach beyond the canvas`() {
        var e = withColour()
        e = BoardEditing.nudge(e, 1e9f, 0f)
        val canvas = e.board.canvas
        assertEquals(canvas.width * (1 + BoardLimits.OFF_CANVAS_REACH), e.selected!!.transform.cx, 0.5f)
    }

    @Test
    fun `size steps multiply and stop at the limits`() {
        var e = withColour()
        e = BoardEditing.scaleBy(e, BoardEditing.Steps.SCALE_FACTOR)
        assertEquals(1.1f, e.selected!!.transform.scale, 0.0001f)
        repeat(200) { e = BoardEditing.scaleBy(e, BoardEditing.Steps.SCALE_FACTOR) }
        assertEquals(BoardLimits.MAX_SCALE, e.selected!!.transform.scale, 0.0001f)
        repeat(300) { e = BoardEditing.scaleBy(e, 1f / BoardEditing.Steps.SCALE_FACTOR) }
        assertEquals(BoardLimits.MIN_SCALE, e.selected!!.transform.scale, 0.0001f)
    }

    @Test
    fun `turning folds into minus 180 to 180`() {
        var e = withColour()
        repeat(13) { e = BoardEditing.turnBy(e, BoardEditing.Steps.TURN_COARSE) }
        assertEquals("195 degrees is 165 the other way", -165f, e.selected!!.transform.rotationDeg, 0.001f)
    }

    @Test
    fun `opacity moves in five percent steps without drifting, and stops at the ends`() {
        var e = withColour()
        repeat(7) { e = BoardEditing.opacityBy(e, -BoardEditing.Steps.OPACITY) }
        assertEquals(0.65f, e.selected!!.opacity, 0f)
        repeat(30) { e = BoardEditing.opacityBy(e, -BoardEditing.Steps.OPACITY) }
        assertEquals(0f, e.selected!!.opacity, 0f)
        repeat(30) { e = BoardEditing.opacityBy(e, BoardEditing.Steps.OPACITY) }
        assertEquals(1f, e.selected!!.opacity, 0f)
    }

    @Test
    fun `centring puts the layer on the middle of the canvas`() {
        var e = withColour()
        e = BoardEditing.nudge(e, 200f, 120f)
        e = BoardEditing.centre(e)
        assertEquals(e.board.canvas.width / 2f, e.selected!!.transform.cx, 0.001f)
        assertEquals(e.board.canvas.height / 2f, e.selected!!.transform.cy, 0.001f)
    }

    @Test
    fun `a finger gesture is one undo step however many moves it had`() {
        val e0 = withColour()
        val id = e0.selectedId!!
        var e = e0
        for (x in 1..20) e = BoardEditing.setTransform(e, id, e0.selected!!.transform.copy(cx = 100f + x), "gesture:1")
        assertEquals(120f, e.selected!!.transform.cx, 0.001f)
        assertEquals(e0.selected!!.transform.cx, BoardEditing.undo(e).selected!!.transform.cx, 0.001f)
        e = BoardEditing.setTransform(e, id, e.selected!!.transform.copy(cx = 300f), "gesture:2")
        assertEquals("the next gesture is its own step", 120f, BoardEditing.undo(e).selected!!.transform.cx, 0.001f)
    }

    // ---- lock, order, duplicate, delete ----

    @Test
    fun `a locked layer takes no edit and can be unlocked`() {
        var e = withColour()
        val id = e.selectedId!!
        e = BoardEditing.setLocked(e, id, true)
        assertTrue(e.selected!!.locked)
        assertSame(e, BoardEditing.nudge(e, 10f, 0f))
        assertSame(e, BoardEditing.opacityBy(e, -0.1f))
        assertSame(e, BoardEditing.delete(e))
        assertSame(e, BoardEditing.restack(e, StackMove.TO_BACK))
        assertSame(e, BoardEditing.setColour(e, BLUE))
        e = BoardEditing.setLocked(e, id, false)
        assertFalse(e.selected!!.locked)
        assertNotNull(BoardEditing.nudge(e, 10f, 0f).board.layer(id))
    }

    @Test
    fun `forward and backward move the layer one place and to front and back move it to the ends`() {
        var e = BoardEditing.addColour(BoardEditing.addColour(withColour(), BLUE), RED)
        val ids = e.board.layers.map { it.id }
        e = BoardEditing.select(e, ids[0])
        e = BoardEditing.restack(e, StackMove.FORWARD)
        assertEquals(listOf(ids[1], ids[0], ids[2]), e.board.layers.map { it.id })
        e = BoardEditing.restack(e, StackMove.TO_FRONT)
        assertEquals(listOf(ids[1], ids[2], ids[0]), e.board.layers.map { it.id })
        assertSame(e, BoardEditing.restack(e, StackMove.FORWARD))
        e = BoardEditing.restack(e, StackMove.TO_BACK)
        assertEquals(ids[0], e.board.layers.first().id)
    }

    @Test
    fun `a duplicate sits above the original, is picked, and is not locked`() {
        var e = withColour()
        e = BoardEditing.setLocked(e, e.selectedId!!, true)
        val original = e.selectedId!!
        e = BoardEditing.duplicate(e)
        assertEquals(2, e.board.layers.size)
        assertTrue(e.selectedId != original)
        assertFalse(e.selected!!.locked)
        assertEquals(e.selectedId, e.board.layers.last().id)
    }

    @Test
    fun `deleting leaves nothing picked and an undo brings the layer and the pick back`() {
        val e0 = withColour()
        val deleted = BoardEditing.delete(e0)
        assertTrue(deleted.board.layers.isEmpty())
        assertNull(deleted.selectedId)
        val back = BoardEditing.undo(deleted)
        assertEquals(1, back.board.layers.size)
        assertNull("the pick is not remembered across an undo", back.selectedId)
    }

    // ---- looks ----

    @Test
    fun `a blend and a colour change are single steps, and a photo has no colour`() {
        var e = withColour()
        e = BoardEditing.setBlend(e, LayerBlend.MULTIPLY)
        assertEquals(LayerBlend.MULTIPLY, e.selected!!.blend)
        e = BoardEditing.setColour(e, BLUE)
        assertEquals(LayerContent.Colour(BLUE), e.selected!!.content)
        val p = BoardEditing.addPhotos(empty(), listOf(ref)).editor
        assertSame(p, BoardEditing.setColour(p, BLUE))
    }

    @Test
    fun `text can be retyped, restyled and resized, and only a text layer takes them`() {
        var e = BoardEditing.addText(empty(), "Hi", RED)
        e = BoardEditing.setText(e, "Hello there")
        e = BoardEditing.setTextBold(e, true)
        e = BoardEditing.setTextAlign(e, TextAlign.CENTRE)
        e = BoardEditing.textSizeBy(e, BoardEditing.Steps.TEXT_SIZE_FACTOR)
        val c = e.selected!!.content as LayerContent.Text
        assertEquals("Hello there", c.text)
        assertTrue(c.bold)
        assertEquals(TextAlign.CENTRE, c.align)
        assertEquals(64f * 1.1f, c.sizeUnits, 0.01f)
        val colour = withColour()
        assertSame(colour, BoardEditing.setText(colour, "x"))
        assertSame(colour, BoardEditing.setTextBold(colour, true))
    }

    @Test
    fun `retyping text in a run is one undo step`() {
        var e = BoardEditing.addText(empty(), "H", RED)
        for (t in listOf("He", "Hel", "Hell", "Hello")) e = BoardEditing.setText(e, t)
        assertEquals("H", (BoardEditing.undo(e).selected!!.content as LayerContent.Text).text)
    }

    // ---- masks ----

    @Test
    fun `changing a mask shape keeps the feather, and a feather is a stepped value`() {
        var e = withColour()
        e = BoardEditing.featherBy(e, BoardEditing.Steps.FEATHER)
        e = BoardEditing.featherBy(e, BoardEditing.Steps.FEATHER)
        assertEquals(0.1f, e.selected!!.mask.feather, 0f)
        e = BoardEditing.setMaskShape(e, MaskShape.Ellipse)
        assertEquals(MaskShape.Ellipse, e.selected!!.mask.shape)
        assertEquals(0.1f, e.selected!!.mask.feather, 0f)
        repeat(20) { e = BoardEditing.featherBy(e, BoardEditing.Steps.FEATHER) }
        assertEquals(BoardLimits.MAX_FEATHER, e.selected!!.mask.feather, 0f)
    }

    @Test
    fun `the corner radius steps only on a rounded mask`() {
        var e = withColour()
        assertSame(e, BoardEditing.radiusBy(e, 0.05f))
        e = BoardEditing.setMaskShape(e, MaskShape.RoundedRect(BoardEditing.Steps.DEFAULT_ROUNDING))
        e = BoardEditing.radiusBy(e, BoardEditing.Steps.RADIUS)
        assertEquals(0.2f, (e.selected!!.mask.shape as MaskShape.RoundedRect).radius, 0f)
    }

    @Test
    fun `mask kinds are told apart`() {
        assertEquals(MaskKind.NONE, MaskKind.of(LayerMask(MaskShape.Full, 0.2f)))
        assertEquals(MaskKind.ROUNDED, MaskKind.of(LayerMask(MaskShape.RoundedRect(0.1f))))
        assertEquals(MaskKind.OVAL, MaskKind.of(LayerMask(MaskShape.Ellipse)))
        assertEquals(MaskKind.SHAPE, MaskKind.of(LayerMask(MaskShape.Polygon(emptyList()))))
        assertEquals(MaskKind.SUBJECT, MaskKind.of(LayerMask(MaskShape.Subject(emptyList()))))
    }

    // ---- subject marks and corner points ----

    private fun photoEditor(crop: CropRect = CropRect.FULL): BoardEditor {
        val e = BoardEditing.addPhotos(empty(), listOf(ref)).editor
        // The photo box is centred on a 1080 square; give it a known place.
        val id = e.selectedId!!
        val laid = BoardOps.setTransform(e.board, id, Transform(500f, 500f))
        val sized = BoardOps.setBox(laid, id, 400f, 200f)
        return BoardEditor.of(sized.let { b -> if (crop == CropRect.FULL) b else b.copy(layers = b.layers.map { it.copy(crop = crop) }) }, id)
    }

    @Test
    fun `a tap on a photo becomes a mark in shares of the photo`() {
        val e = photoEditor()
        val layer = e.selected!!
        // The box spans x 300..700, y 400..600: a tap at its centre is the middle of the photo.
        val mark = BoardEditing.subjectMark(layer, 500f, 500f, true)!!
        assertTrue(mark.foreground)
        assertEquals(0.5f, mark.stroke.points.single().x, 0.001f)
        assertEquals(0.5f, mark.stroke.points.single().y, 0.001f)
        val corner = BoardEditing.subjectMark(layer, 301f, 401f, false)!!
        assertFalse(corner.foreground)
        assertEquals(0.0025f, corner.stroke.points.single().x, 0.001f)
        assertNull("outside the box", BoardEditing.subjectMark(layer, 100f, 100f, true))
    }

    @Test
    fun `a mark on a cropped photo is in shares of the whole photo`() {
        val e = photoEditor(CropRect(0.5f, 0.25f, 1f, 0.75f))
        val mark = BoardEditing.subjectMark(e.selected!!, 500f, 500f, true)!!
        assertEquals(0.75f, mark.stroke.points.single().x, 0.001f)
        assertEquals(0.5f, mark.stroke.points.single().y, 0.001f)
        // And back: where to draw the mark on the box.
        val back = BoardEditing.markInBox(e.selected!!, mark)!!
        assertEquals(0.5f, back.x, 0.001f)
        assertEquals(0.5f, back.y, 0.001f)
    }

    @Test
    fun `marks pile up in the subject mask, can be undone one by one, and only a photo takes them`() {
        var e = photoEditor()
        val a = BoardEditing.subjectMark(e.selected!!, 450f, 450f, true)!!
        val b = BoardEditing.subjectMark(e.selected!!, 650f, 550f, false)!!
        e = BoardEditing.addSubjectMark(e, a)
        e = BoardEditing.addSubjectMark(e, b)
        val shape = e.selected!!.mask.shape as MaskShape.Subject
        assertEquals(listOf<SelectionOp>(a, b), shape.ops)
        assertTrue(shape.isSolvable)
        e = BoardEditing.undoSubjectMark(e)
        assertEquals(listOf<SelectionOp>(a), (e.selected!!.mask.shape as MaskShape.Subject).ops)
        val colour = withColour()
        assertSame(colour, BoardEditing.addSubjectMark(colour, a))
        assertNull(BoardEditing.subjectMark(colour.selected!!, 500f, 500f, true))
    }

    @Test
    fun `a corner point is a share of the box and a tap outside it is ignored`() {
        val e = photoEditor()
        val p = BoardEditing.boxPoint(e.selected!!, 400f, 450f)!!
        assertEquals(0.25f, p.x, 0.001f)
        assertEquals(0.25f, p.y, 0.001f)
        assertNull(BoardEditing.boxPoint(e.selected!!, 10f, 10f))
    }

    @Test
    fun `a corner point follows a turned layer`() {
        val e = photoEditor()
        val turned = e.selected!!.let { it.copy(transform = it.transform.copy(rotationDeg = 90f)) }
        // Turned a quarter clockwise, the box's right end points down: the point 100 above the centre is its left-middle... check by the inverse.
        val p = BoardEditing.boxPoint(turned, 500f, 500f - 100f)!!
        assertEquals(0.25f, p.x, 0.001f)
        assertEquals(0.5f, p.y, 0.001f)
    }

    // ---- presets ----

    @Test
    fun `every preset is a closed outline of three or more points inside the box`() {
        for (preset in MaskPresets.Preset.entries) {
            val pts = MaskPresets.points(preset)
            assertTrue("$preset has ${pts.size} points", pts.size in 3..BoardLimits.MAX_POLYGON_POINTS)
            assertTrue(pts.all { it.x in 0f..1f && it.y in 0f..1f })
            assertEquals("no point repeated", pts.size, pts.toSet().size)
        }
    }

    @Test
    fun `a preset shows its middle and hides its corner`() {
        for (preset in MaskPresets.Preset.entries) {
            val mask = LayerMask(MaskShape.Polygon(MaskPresets.points(preset)))
            assertTrue("$preset shows the middle", MaskGeometry.shows(mask, 100f, 100f, 50f, 52f))
            assertFalse("$preset hides the top left corner", MaskGeometry.shows(mask, 100f, 100f, 1f, 1f))
        }
    }

    // ---- the board ----

    @Test
    fun `background and canvas edits are steps of their own`() {
        var e = withColour()
        e = BoardEditing.setBackground(e, BoardBackground.Transparent)
        assertTrue(e.board.needsAlpha)
        e = BoardEditing.setCanvas(e, CanvasPreset.STORY_9_16.size)
        assertEquals(CanvasPreset.STORY_9_16.size, e.board.canvas)
        assertSame(e, BoardEditing.setCanvas(e, CanvasPreset.STORY_9_16.size))
        assertEquals(CanvasPreset.SQUARE.size, BoardEditing.undo(e).board.canvas)
        assertTrue("the background is still see-through one step back", BoardEditing.undo(e).board.needsAlpha)
    }

    @Test
    fun `undo and redo are no-ops at the ends`() {
        val e = empty()
        assertSame(e, BoardEditing.undo(e))
        assertSame(e, BoardEditing.redo(e))
        assertFalse(e.canUndo)
    }

    @Test
    fun `an editor opened with a pick that is not on the board picks nothing`() {
        assertNull(BoardEditor.of(board(layer(1, 0f, 0f)), 9L).selectedId)
        assertEquals(1L, BoardEditor.of(board(layer(1, 0f, 0f)), 1L).selectedId)
        assertNotNull(photo(1))
        assertNotNull(NormPoint(0f, 0f))
    }
}
