package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The touch layer against the pure maths: a 400 x 300 dp area at 2x is 800 x 600 px, and an
 * 800 x 600 wall photo fits it at scale 1, so a 200 cm wall is 4 px per cm and the edge zone
 * (56 + 4 dp) is 120 px on every side. Gestures are injected; how it FEELS is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class WallCanvasTest {

    @get:Rule
    val compose = createComposeRule()

    private val wallImage = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).asImageBitmap()

    // Share-of-width centres: (0.5, 0.375) is the pixel (400, 300); (0.05, 0.375) is (40, 300), in the edge zone.
    private val middle = WallFrame(1, 0.5f, 0.375f, 40f, 50f)
    private val atEdge = WallFrame(2, 0.05f, 0.375f, 20f, 20f)

    private var draft by mutableStateOf(WallDraft(step = WallStep.HANG, wallId = 9, wallWidthCm = 200f))
    private var marks = mutableListOf<Offset>()

    private fun show(step: WallStep = WallStep.HANG, frames: List<WallFrame> = listOf(middle, atEdge)) {
        draft = draft.copy(step = step, frames = frames)
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                WallCanvas(
                    wall = wallImage,
                    draft = draft,
                    pictures = emptyMap(),
                    marks = emptyList(),
                    onMark = { marks += it },
                    onDraft = { draft = it },
                    modifier = Modifier.size(400.dp, 300.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `dragging a frame moves it by the share of the wall photo the finger moved`() {
        show()
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { swipe(Offset(400f, 300f), Offset(500f, 300f), durationMillis = 200) }
        compose.waitForIdle()
        assertEquals(0.5f + 100f / 800f, draft.frames[0].cx, 0.01f)
        assertEquals(0.375f, draft.frames[0].cy, 0.01f)
        assertEquals(0, draft.selected)
    }

    @Test
    fun `touching a frame selects it and a drag on bare wall moves nothing`() {
        show()
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { swipe(Offset(400f, 300f), Offset(400f, 300f), durationMillis = 100) }
        // Bare wall: well outside the 160 x 200 px frame and its grab slop.
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { swipe(Offset(650f, 450f), Offset(600f, 450f), durationMillis = 200) }
        compose.waitForIdle()
        assertEquals(listOf(middle, atEdge).map { it.cx }, draft.frames.map { it.cx })
    }

    @Test
    fun `a drag that starts in the shell's edge zone never reaches the frame`() {
        show()
        // The second frame sits at pixel x = 40, inside the 120 px zone: not grabbable by touch.
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { swipe(Offset(40f, 300f), Offset(300f, 300f), durationMillis = 200) }
        compose.waitForIdle()
        assertEquals(0.05f, draft.frames[1].cx, 1e-4f)
        assertEquals(0, draft.selected)
    }

    @Test
    fun `a pinch with two fingers enlarges the selected frame and keeps its shape`() {
        show(frames = listOf(middle))
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput {
            pinch(Offset(380f, 300f), Offset(300f, 300f), Offset(420f, 300f), Offset(500f, 300f), durationMillis = 300)
        }
        compose.waitForIdle()
        val f = draft.frames[0]
        assertTrue("grew: ${f.wCm}", f.wCm > 45f)
        assertEquals(40f / 50f, f.wCm / f.hCm, 0.01f)
    }

    @Test
    fun `in the scale step a tap marks the photo's own pixel and moves nothing`() {
        show(step = WallStep.SCALE)
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { click(Offset(400f, 300f)) }
        compose.waitForIdle()
        assertEquals(1, marks.size)
        assertEquals(400f, marks[0].x, 1.5f)
        assertEquals(300f, marks[0].y, 1.5f)
        assertEquals(listOf(middle, atEdge), draft.frames)
    }

    @Test
    fun `a tap outside the photo's edge zone inset marks nothing`() {
        show(step = WallStep.SCALE)
        compose.onNodeWithTag(WALL_CANVAS_TAG).performTouchInput { click(Offset(40f, 300f)) }
        compose.waitForIdle()
        assertTrue(marks.isEmpty())
        assertNull(marks.firstOrNull())
    }
}
