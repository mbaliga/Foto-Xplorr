package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The banner text, which is derived from the editor state and must never contradict it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiftHintTest {

    private fun stroke() = SelectionOp.Hint(true, BrushStroke(listOf(NormPoint(0.5f, 0.5f)), 0.03f))

    @Test
    fun `an empty selection never claims a preview`() {
        val editor = LiftEditorState()
        editor.previewCutout = true // the stale flag Undo used to leave behind
        assertEquals("Tap or scribble over the subject", hintFor(editor))
    }

    @Test
    fun `undo back to empty reads as empty even with the preview flag set`() {
        val editor = LiftEditorState()
        editor.apply(stroke())
        editor.previewCutout = true
        assertEquals("Previewing the cut-out", hintFor(editor))
        editor.session = editor.session.undo()
        assertEquals("Tap or scribble over the subject", hintFor(editor))
    }

    @Test
    fun `a failed solve is told to the person instead of being silent`() {
        val editor = LiftEditorState()
        editor.apply(stroke())
        editor.solveFailure = LiftFailure.OutOfMemory
        val hint = hintFor(editor)
        assertNotEquals("Previewing the cut-out", hint)
        assertEquals(true, hint.startsWith(LiftFailure.OutOfMemory.userMessage()))
    }

    @Test
    fun `working wins over everything`() {
        val editor = LiftEditorState()
        editor.solving = true
        editor.solveFailure = LiftFailure.OutOfMemory
        assertEquals("Working…", hintFor(editor))
    }
}
