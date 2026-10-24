package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection history: undo/redo as pure state, and the property that makes it trustworthy --
 * replaying the history gives back EXACTLY the mask that existed before an operation, because the
 * mask is derived from the history rather than stored beside it.
 */
class SelectionSessionTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val width = 64
    private val height = 48

    /** A red square on a blue field. */
    private val pixels = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if (x in 20..40 && y in 12..32) argb(220, 30, 30) else argb(30, 40, 200)
    }

    private fun tap(x: Float, y: Float, foreground: Boolean = true) =
        SelectionOp.Hint(foreground, BrushStroke(listOf(NormPoint(x, y)), 0.03f))

    private fun paint(x: Float, y: Float, add: Boolean, radius: Float = 0.06f) =
        SelectionOp.Paint(add, BrushStroke(listOf(NormPoint(x, y)), radius))

    private fun replay(ops: List<SelectionOp>) = SelectionOps.replay(pixels, width, height, ops)

    // ---- session mechanics ----

    @Test
    fun `a new session has nothing to undo or redo`() {
        val session = SelectionSession()
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
        assertTrue(session.isEmpty)
        assertSame("undo on nothing is a no-op", session, session.undo())
        assertSame(session, session.redo())
    }

    @Test
    fun `apply undo and redo walk the history`() {
        val a = tap(0.5f, 0.45f)
        val b = paint(0.5f, 0.45f, add = false)
        val session = SelectionSession().apply(a).apply(b)
        assertEquals(listOf(a, b), session.ops)

        val undone = session.undo()
        assertEquals(listOf(a), undone.ops)
        assertTrue(undone.canRedo)

        val redone = undone.redo()
        assertEquals(listOf(a, b), redone.ops)
        assertFalse(redone.canRedo)
    }

    @Test
    fun `a new operation after an undo discards the redo stack`() {
        val a = tap(0.5f, 0.45f)
        val b = paint(0.2f, 0.2f, add = true)
        val c = paint(0.8f, 0.8f, add = true)
        val session = SelectionSession().apply(a).apply(b).undo().apply(c)
        assertEquals(listOf(a, c), session.ops)
        assertFalse(session.canRedo)
    }

    @Test
    fun `reset empties both stacks and the original session is untouched`() {
        val session = SelectionSession().apply(tap(0.5f, 0.5f)).apply(tap(0.4f, 0.4f)).undo()
        val cleared = session.reset()
        assertTrue(cleared.isEmpty)
        assertFalse(cleared.canUndo)
        assertFalse(cleared.canRedo)
        assertNotSame(session, cleared)
        assertTrue("sessions are immutable", session.canUndo && session.canRedo)
    }

    @Test
    fun `hasForeground is true only once a subject hint exists`() {
        val bg = tap(0.1f, 0.1f, foreground = false)
        assertFalse(SelectionSession().apply(bg).apply(paint(0.5f, 0.5f, add = true)).hasForeground)
        assertTrue(SelectionSession().apply(bg).apply(tap(0.5f, 0.45f)).hasForeground)
    }

    // ---- undo restores the exact mask ----

    @Test
    fun `undoing a hint restores the exact previous mask`() {
        val first = tap(0.5f, 0.45f)
        val second = tap(0.35f, 0.5f)
        val before = replay(listOf(first))
        val session = SelectionSession().apply(first).apply(second)

        val afterUndo = replay(session.undo().ops)

        assertTrue(before.alpha.contentEquals(afterUndo.alpha))
    }

    @Test
    fun `undoing a paint restores the exact previous mask`() {
        val hint = tap(0.5f, 0.45f)
        val erase = paint(0.5f, 0.45f, add = false, radius = 0.08f)
        val withoutErase = replay(listOf(hint))
        val withErase = replay(listOf(hint, erase))

        assertFalse("the erase did something", withoutErase.alpha.contentEquals(withErase.alpha))
        val session = SelectionSession().apply(hint).apply(erase)
        assertTrue(withoutErase.alpha.contentEquals(replay(session.undo().ops).alpha))
        // and redo brings the erased version back exactly
        assertTrue(withErase.alpha.contentEquals(replay(session.undo().redo().ops).alpha))
    }

    @Test
    fun `undoing everything leaves nothing selected`() {
        val session = SelectionSession().apply(tap(0.5f, 0.45f)).undo()
        assertTrue(replay(session.ops).alpha.all { it == 0f })
    }

    // ---- replay semantics ----

    @Test
    fun `no foreground hint selects nothing`() {
        assertTrue(replay(emptyList()).alpha.all { it == 0f })
        assertTrue(replay(listOf(tap(0.1f, 0.1f, foreground = false))).alpha.all { it == 0f })
    }

    @Test
    fun `a tap on the square selects the square`() {
        val mask = replay(listOf(tap(0.5f, 0.45f)))
        assertTrue(mask.isIncluded(22 * width + 30))
        assertFalse(mask.isIncluded(2 * width + 2))
    }

    @Test
    fun `paint is applied after the solve and wins over it`() {
        val hint = tap(0.5f, 0.45f)
        // Erase the centre of the square, then add a patch of plain background elsewhere.
        val mask = replay(
            listOf(
                hint,
                paint(0.47f, 0.46f, add = false, radius = 0.05f),
                paint(0.1f, 0.1f, add = true, radius = 0.04f),
            ),
        )
        assertFalse("erased the middle of the square", mask.isIncluded(22 * width + 30))
        assertTrue("painted a patch of background in", mask.isIncluded(5 * width + 6))
        assertTrue("the rest of the square survives", mask.isIncluded(14 * width + 22))
    }

    @Test
    fun `a paint made before a later hint still applies`() {
        // Corrections are sticky: a later hint re-solves the surroundings but must not undo them.
        val erase = paint(0.47f, 0.46f, add = false, radius = 0.05f)
        val mask = replay(listOf(tap(0.5f, 0.45f), erase, tap(0.6f, 0.5f)))
        assertFalse(mask.isIncluded(22 * width + 30))
    }

    @Test
    fun `a later background hint over a subject hint wins those pixels`() {
        val ops = listOf(tap(0.5f, 0.45f), tap(0.5f, 0.45f, foreground = false))
        val fg = BooleanArray(width * height)
        val bg = BooleanArray(width * height)
        for (op in ops) {
            op as SelectionOp.Hint
            SelectionOps.stamp(op.stroke, width, height) { i ->
                if (op.foreground) { fg[i] = true; bg[i] = false } else { bg[i] = true; fg[i] = false }
            }
        }
        assertTrue("the second hint owns the pixel", bg[22 * width + 32] && !fg[22 * width + 32])
    }

    // ---- brush geometry ----

    @Test
    fun `brush radius is a fraction of the long edge on both axes`() {
        val w = 200
        val h = 100
        val covered = BooleanArray(w * h)
        SelectionOps.stamp(BrushStroke(listOf(NormPoint(0.5f, 0.5f)), 0.1f), w, h) { covered[it] = true }

        // 0.1 of the 200px long edge is a 20px radius, on the short axis too.
        assertTrue(covered[50 * w + 100 + 18])
        assertTrue(covered[(50 + 18) * w + 100])
        assertFalse(covered[50 * w + 100 + 22])
        assertFalse(covered[(50 + 22) * w + 100])
        val area = covered.count { it }
        assertTrue("area $area should be about pi*20^2", area in 1150..1350)
    }

    @Test
    fun `a stroke covers the whole path between its points`() {
        val w = 100
        val h = 50
        val covered = BooleanArray(w * h)
        val stroke = BrushStroke(listOf(NormPoint(0.1f, 0.5f), NormPoint(0.9f, 0.5f)), 0.02f)
        SelectionOps.stamp(stroke, w, h) { covered[it] = true }
        for (x in 12..88) assertTrue("gap at x=$x", covered[25 * w + x])
        assertFalse(covered[5 * w + 50])
    }

    @Test
    fun `points outside the image are clamped onto it`() {
        val w = 40
        val h = 40
        val covered = BooleanArray(w * h)
        SelectionOps.stamp(BrushStroke(listOf(NormPoint(-3f, 0.5f), NormPoint(9f, 0.5f)), 0.03f), w, h) { covered[it] = true }
        assertTrue(covered[20 * w + 0])
        assertTrue(covered[20 * w + 39])
    }

    @Test
    fun `a tiny brush still marks at least the pixel under it`() {
        val covered = BooleanArray(100)
        SelectionOps.stamp(BrushStroke(listOf(NormPoint(0.55f, 0.55f)), 0.0001f), 10, 10) { covered[it] = true }
        assertTrue(covered.any { it })
    }

    // ---- sizes and resampling ----

    @Test
    fun `working size keeps the aspect ratio and never upscales`() {
        assertEquals(512 to 384, SelectionOps.workingSize(1024, 768, 512))
        assertEquals(288 to 512, SelectionOps.workingSize(1080, 1920, 512))
        assertEquals(300 to 200, SelectionOps.workingSize(300, 200, 512))
        assertEquals(1 to 1, SelectionOps.workingSize(0, 0, 512))
        assertEquals(512 to 1, SelectionOps.workingSize(4000, 2, 512))
    }

    @Test
    fun `resampling an alpha plane stays within 0 to 1 and keeps the shape in place`() {
        // 4x4 mask, left half set.
        val alpha = FloatArray(16) { if (it % 4 < 2) 1f else 0f }
        val up = SelectionOps.resampleAlpha(SubjectSegmentation.Mask(4, 4, alpha), 16, 16)
        assertEquals(16, up.width)
        assertTrue(up.alpha.all { it in 0f..1f })
        assertEquals(1f, up.alpha[8 * 16 + 1], 0.0001f)
        assertEquals(0f, up.alpha[8 * 16 + 14], 0.0001f)
        // monotonically non-increasing across the edge
        for (x in 1 until 16) assertTrue(up.alpha[8 * 16 + x] <= up.alpha[8 * 16 + x - 1] + 1e-6f)
        // the mid-line of the transition sits at the original edge, not half a pixel off
        assertTrue(up.alpha[8 * 16 + 7] > 0.3f && up.alpha[8 * 16 + 8] < 0.7f)
    }

    @Test
    fun `resampling to the same size returns the same mask`() {
        val mask = SubjectSegmentation.Mask(3, 3, FloatArray(9) { 0.5f })
        assertSame(mask, SelectionOps.resampleAlpha(mask, 3, 3))
    }

    // ---- turning a mask into pixels ----

    @Test
    fun `cutPixels is transparent outside, original colour inside, and cropped with padding`() {
        val w = 10
        val h = 8
        val photo = IntArray(w * h) { argb(10 + it, 20, 30) }
        val alpha = FloatArray(w * h)
        for (y in 3..4) for (x in 4..6) alpha[y * w + x] = 1f
        val cut = SelectionOps.cutPixels(photo, w, h, SubjectSegmentation.Mask(w, h, alpha), padding = 1)!!

        assertEquals(5, cut.width) // x 3..7
        assertEquals(4, cut.height) // y 2..5
        // inside: first selected pixel is photo (4,3), which lands at (1,1) in the crop
        assertEquals(photo[3 * w + 4], cut.pixels[1 * cut.width + 1])
        // padding ring is fully transparent
        assertEquals(0, cut.pixels[0])
        assertEquals(0, (cut.pixels[0] ushr 24))
    }

    @Test
    fun `cutPixels carries the mask's fractional alpha into the alpha channel`() {
        val photo = IntArray(4) { argb(100, 100, 100) }
        val cut = SelectionOps.cutPixels(photo, 2, 2, SubjectSegmentation.Mask(2, 2, floatArrayOf(1f, 0.5f, 0f, 0f)), padding = 0)!!
        assertEquals(2, cut.width)
        assertEquals(255, cut.pixels[0] ushr 24)
        assertEquals(128, cut.pixels[1] ushr 24)
    }

    @Test
    fun `cutPixels applies a small working mask to a larger photo`() {
        val photo = IntArray(20 * 20) { argb(1, 2, 3) }
        val mask = SubjectSegmentation.Mask(10, 10, FloatArray(100) { if (it % 10 < 5) 1f else 0f })
        val cut = SelectionOps.cutPixels(photo, 20, 20, mask, padding = 0)!!
        // The left half of a 20px photo: 10 columns, plus the one pixel of bilinear fall-off that
        // upsampling a hard mask edge spills across the seam.
        assertTrue("width was ${cut.width}", cut.width in 10..12)
        assertEquals(20, cut.height)
    }

    @Test
    fun `an empty mask cuts nothing`() {
        assertEquals(null, SelectionOps.cutPixels(IntArray(4), 2, 2, SubjectSegmentation.Mask(2, 2, FloatArray(4)), 2))
    }

    @Test
    fun `overlay pixels wash only the selected area`() {
        val overlay = SelectionOps.overlayPixels(SubjectSegmentation.Mask(2, 1, floatArrayOf(1f, 0f)), 0x112233, 100)
        assertEquals((100 shl 24) or 0x112233, overlay[0])
        assertEquals(0, overlay[1])
    }
}
