package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure rules of what a hold on a grid tile does -- see [LongPressResolver]. */
class LongPressResolverTest {

    @Test
    fun `with no selection each setting resolves to its own outcome`() {
        val expected = mapOf(
            GridLongPress.PEEK to LongPressOutcome.Peek,
            GridLongPress.SELECT to LongPressOutcome.ToggleSelection,
            GridLongPress.FAVOURITE to LongPressOutcome.Run(MediaContextAction.TOGGLE_FAVORITE),
            GridLongPress.SHARE to LongPressOutcome.Run(MediaContextAction.SHARE),
            GridLongPress.EDIT to LongPressOutcome.Run(MediaContextAction.EDIT),
            GridLongPress.NOTHING to LongPressOutcome.None,
        )
        assertEquals("every setting is covered", GridLongPress.entries.toSet(), expected.keys)
        expected.forEach { (setting, outcome) ->
            assertEquals(setting.name, outcome, LongPressResolver.resolve(setting, selectionActive = false))
        }
    }

    @Test
    fun `while selecting a hold extends the selection whatever the setting says`() {
        GridLongPress.entries.filter { it != GridLongPress.NOTHING }.forEach { setting ->
            assertEquals(
                setting.name,
                LongPressOutcome.ToggleSelection,
                LongPressResolver.resolve(setting, selectionActive = true),
            )
        }
    }

    @Test
    fun `nothing stays inert even while selecting`() {
        assertEquals(LongPressOutcome.None, LongPressResolver.resolve(GridLongPress.NOTHING, selectionActive = true))
    }

    @Test
    fun `in the trash favourite share and edit fall back to the preview`() {
        listOf(GridLongPress.FAVOURITE, GridLongPress.SHARE, GridLongPress.EDIT).forEach {
            assertEquals(it.name, GridLongPress.PEEK, LongPressResolver.effectiveFor(it, inTrash = true))
        }
    }

    @Test
    fun `in the trash preview select and nothing are unchanged and outside it nothing changes`() {
        listOf(GridLongPress.PEEK, GridLongPress.SELECT, GridLongPress.NOTHING).forEach {
            assertEquals(it.name, it, LongPressResolver.effectiveFor(it, inTrash = true))
        }
        GridLongPress.entries.forEach {
            assertEquals(it.name, it, LongPressResolver.effectiveFor(it, inTrash = false))
        }
    }

    @Test
    fun `the retired peek switch maps on to preview and off to select`() {
        assertEquals(GridLongPress.PEEK, LongPressResolver.fromLegacyPeekFlag(true))
        assertEquals(GridLongPress.SELECT, LongPressResolver.fromLegacyPeekFlag(false))
    }

    @Test
    fun `no setting offers trash`() {
        GridLongPress.entries.forEach { setting ->
            listOf(false, true).forEach { selecting ->
                val outcome = LongPressResolver.resolve(setting, selecting)
                assertEquals(
                    "$setting selecting=$selecting",
                    false,
                    outcome is LongPressOutcome.Run && outcome.action == MediaContextAction.MOVE_TO_TRASH,
                )
            }
        }
    }

    @Test
    fun `a screen reader's long click never starts a preview it could not release`() {
        val semantics = LongPressResolver.longClickSemantics(GridLongPress.PEEK, selectionActive = false)
        assertNotNull(semantics)
        assertEquals("Select", semantics!!.label)
        assertEquals(LongPressOutcome.ToggleSelection, semantics.outcome)
    }

    @Test
    fun `a screen reader is offered the configured action under its own name`() {
        assertEquals("Select", LongPressResolver.longClickSemantics(GridLongPress.SELECT, false)!!.label)
        assertEquals("Favourite", LongPressResolver.longClickSemantics(GridLongPress.FAVOURITE, false)!!.label)
        assertEquals("Share", LongPressResolver.longClickSemantics(GridLongPress.SHARE, false)!!.label)
        assertEquals("Edit", LongPressResolver.longClickSemantics(GridLongPress.EDIT, false)!!.label)
        assertEquals(
            LongPressOutcome.Run(MediaContextAction.EDIT),
            LongPressResolver.longClickSemantics(GridLongPress.EDIT, false)!!.outcome,
        )
    }

    @Test
    fun `a screen reader is offered no long click for nothing and a toggle while selecting`() {
        assertNull(LongPressResolver.longClickSemantics(GridLongPress.NOTHING, false))
        assertNull(LongPressResolver.longClickSemantics(GridLongPress.NOTHING, true))
        assertEquals(
            LongPressOutcome.ToggleSelection,
            LongPressResolver.longClickSemantics(GridLongPress.EDIT, selectionActive = true)!!.outcome,
        )
    }

    @Test
    fun `a tap opens, or toggles while selecting`() {
        assertEquals("Open", LongPressResolver.tapLabel(selectionActive = false))
        assertEquals("Add or remove from selection", LongPressResolver.tapLabel(selectionActive = true))
    }
}
