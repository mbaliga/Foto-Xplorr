package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scale step's reference and marks across a trip to the hang step and back. The wall width
 * follows the marks only when the person marks or changes the reference, never because the panel
 * was rebuilt. Taps are injected; where they land on a real phone is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class WallScaleStepTest {

    @get:Rule
    val compose = createComposeRule()

    private val photo = WallPhoto(DecodedBitmap(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888), 800, 600, false), focal35mm = null)

    private var draft by mutableStateOf(
        WallDraft(step = WallStep.SCALE, wallId = 1, frames = listOf(WallFrame(2, 0.5f, 0.375f, 40f, 50f))),
    )

    private fun show() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                WallPreviewScreen(draft, emptyList(), photo, emptyMap(), onEdit = { change -> draft = change(draft) }, onClose = {})
            }
        }
        compose.waitForIdle()
    }

    private fun markTwoEnds() {
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { click(Offset(centerX - 120f, centerY)) }
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { click(Offset(centerX + 120f, centerY)) }
        compose.waitForIdle()
    }

    private fun toHangAndBack() {
        compose.onNodeWithText(WallCopy.STEP_HANG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(WallCopy.REDO_SCALE).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `going to hang and back does not recalibrate the wall from the old marks with the default reference`() {
        show()
        compose.onNodeWithText(WallCopy.reference(ReferenceObject.DOOR)).performClick()
        markTwoEnds()
        val marked = draft.wallWidthCm
        assertNotEquals(0f, marked)

        toHangAndBack()

        assertEquals(WallStep.SCALE, draft.step)
        assertEquals(marked, draft.wallWidthCm, 0f)
    }

    @Test
    fun `a width typed after marking is still there after a trip to hang and back`() {
        show()
        markTwoEnds()
        compose.onNode(hasText(WallCopy.WALL_WIDTH) and hasSetTextAction()).performTextClearance()
        compose.onNode(hasText(WallCopy.WALL_WIDTH) and hasSetTextAction()).performTextInput("321")
        compose.waitForIdle()
        assertEquals(321f, draft.wallWidthCm, 0f)

        toHangAndBack()

        assertEquals(321f, draft.wallWidthCm, 0f)
    }

    @Test
    fun `changing the reference after marking still recalibrates`() {
        show()
        markTwoEnds()
        val a4 = draft.wallWidthCm
        compose.onNodeWithText(WallCopy.reference(ReferenceObject.DOOR)).performClick()
        compose.waitForIdle()
        assertEquals(a4 * ReferenceObject.DOOR.cm / ReferenceObject.A4_LONG.cm, draft.wallWidthCm, 0.5f)
    }

    @Test
    fun `a typed distance that gives no usable width clears the width the marks gave, and says the scale is not set`() {
        show()
        markTwoEnds()
        assertNotEquals(0f, draft.wallWidthCm)

        compose.onNode(hasText(WallCopy.DISTANCE) and hasSetTextAction()).performTextInput("5")
        compose.waitForIdle()

        assertEquals(5f, draft.distanceCm, 0f)
        assertEquals(0f, draft.wallWidthCm, 0f)
        compose.onNodeWithText(WallCopy.NEED_SCALE).assertExists()
    }
}
