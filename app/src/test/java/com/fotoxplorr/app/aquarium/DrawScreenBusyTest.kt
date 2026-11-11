package com.fotoxplorr.app.aquarium

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawing step on its own, with [DrawScreen]'s inputs set directly: while a Swim save is in
 * flight nothing on the board can change the sketch, and a ready shape that does not fit is
 * refused with a sentence. Does not show how any of it looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h900dp-xhdpi")
class DrawScreenBusyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sketchOf(n: Int): Sketch {
        var s = Sketch()
        repeat(n) { s = s.add(List(5) { i -> NormPoint(0.1f + 0.1f * i, 0.1f + 0.01f * it) }, 0xFF000000.toInt(), 0.02f) }
        return s
    }

    private fun show(initial: DrawDraft, busy: () -> Boolean = { false }, onDraft: (DrawDraft) -> Unit = {}): () -> DrawDraft {
        var draft by mutableStateOf(initial)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                DrawScreen(
                    draft = draft,
                    onDraft = { change -> draft = change(draft).also(onDraft) },
                    tankFull = false,
                    busy = busy(),
                    message = null,
                    onSwim = {},
                    onBack = {},
                )
            }
        }
        return { draft }
    }

    @Test
    fun `while a save is in flight the board, colours, brushes and shapes do nothing`() {
        var busy by mutableStateOf(true)
        val draft = show(DrawDraft(sketchOf(2)), busy = { busy })

        compose.onNodeWithContentDescription(AquariumCopy.board(2)).performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.6f))
        }
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).assertIsNotEnabled()
        compose.onNodeWithText(AquariumCopy.BRUSH_THICK).assertIsNotEnabled()
        compose.onNodeWithText(AquariumCopy.HEAD_LEFT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(AquariumCopy.COLOR_BLUE).assertIsNotEnabled()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.COLOR_BLUE).performClick()

        assertEquals(2, draft().sketch.strokes.size)
        assertEquals(DrawDraft.DEFAULT_COLOR_INDEX, draft().colorIndex)

        // Once the save is over the same controls work again.
        busy = false
        compose.waitForIdle()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).assertIsEnabled()
        compose.onNodeWithContentDescription(AquariumCopy.board(2)).performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.6f))
        }
        assertEquals(3, draft().sketch.strokes.size)
    }

    @Test
    fun `a ready shape that does not fit is refused with a sentence and adds nothing`() {
        val draft = show(DrawDraft(sketchOf(Sketch.MAX_STROKES - 2)))
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithText(AquariumCopy.STROKES_FULL).assertExists()
        assertEquals(Sketch.MAX_STROKES - 2, draft().sketch.strokes.size)
    }

    @Test
    fun `a ready shape that just fits is added, and the full board then says so`() {
        val draft = show(DrawDraft(sketchOf(Sketch.MAX_STROKES - 3)))
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        assertEquals(Sketch.MAX_STROKES, draft().sketch.strokes.size)
        compose.onNodeWithText(AquariumCopy.STROKES_FULL).assertExists()
    }
}
