package com.fotoxplorr.app.textselect

import com.fotoxplorr.app.recognition.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A selection made on the block-only layout, carried onto the word-level layout that arrives a
 * moment later. The two are NOT in the same frame: the block-only one is laid over the photo's
 * full-size upright frame, the word-level one over its 2048 px decode.
 */
class TextSelectionCarryOverTest {

    @Test
    fun `a block selected on a bigger frame lands on the same words of a smaller one`() {
        // The stored boxes, normalised: the first paragraph and the exit line of page().
        val stored = listOf(
            TextBlock("PLATFORM 9\nNorthern line", 100f / FRAME_W, 100f / FRAME_H, 350f / FRAME_W, 180f / FRAME_H),
            TextBlock("Exit only left", 600f / FRAME_W, 300f / FRAME_H, 880f / FRAME_W, 330f / FRAME_H),
        )
        // The photo is 4000 x 2000 upright; the word-level read is the 1024 x 512 of page().
        val block = TextLayout.fromLegacyBlocks(stored, 4000, 2000)
        val words = page()

        val onBlocks = TextSelection.NONE.selectBlock(block, 1)
        val carried = onBlocks.carriedOver(block, words)

        assertEquals("Exit only left", carried.text(words))
    }

    @Test
    fun `a selection in the layout it was made on is returned as it is`() {
        val layout = page()
        val selection = TextSelection.NONE.selectBlock(layout, 0)
        assertEquals(selection, selection.carriedOver(layout, layout))
    }

    @Test
    fun `an empty selection stays empty`() {
        assertEquals(TextSelection.NONE, TextSelection.NONE.carriedOver(page(), page()))
    }
}
