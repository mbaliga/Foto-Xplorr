package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Move buttons on the hang panel against the wall photo's own shape: a frame can be walked to the
 * photo's edge and no further. Clicks are injected; how the buttons feel is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class WallMoveButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    // 800 x 600: 0.75 of its width tall.
    private val photo = WallPhoto(DecodedBitmap(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888), 800, 600, false), focal35mm = null)

    private var draft by mutableStateOf(
        WallDraft(step = WallStep.HANG, wallId = 1, wallWidthCm = 200f, frames = listOf(WallFrame(2, 0.5f, 0.7f, 40f, 50f))),
    )

    @Test
    fun `Move down stops at the bottom of the wall photo, and Move up at its top`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                WallPreviewScreen(draft, emptyList(), photo, emptyMap(), onEdit = { change -> draft = change(draft) }, onClose = {})
            }
        }
        compose.waitForIdle()

        // One click is 2 cm of a 200 cm wall: 0.01 of the photo's width.
        repeat(12) { compose.onNodeWithContentDescription(WallCopy.MOVE_DOWN).performClick() }
        compose.waitForIdle()
        assertEquals(0.75f, draft.frames[0].cy, 1e-5f)

        repeat(90) { compose.onNodeWithContentDescription(WallCopy.MOVE_UP).performClick() }
        compose.waitForIdle()
        assertEquals(0f, draft.frames[0].cy, 1e-5f)
    }
}
