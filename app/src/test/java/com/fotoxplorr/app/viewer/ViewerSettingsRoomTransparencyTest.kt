package com.fotoxplorr.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.TransparencyBackground
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The view-options row for the background behind transparent photos (owner, #17): the chrome
 * button exists only while a see-through photo is on view, so the choice must be reachable here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerSettingsRoomTransparencyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the row shows the current choice and a tap moves to the next`() {
        var mode by mutableStateOf(TransparencyBackground.CHECKERBOARD)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                ViewerSettingsRoom(
                    slideshowIntervalSeconds = 4,
                    blurSensitive = true,
                    showFilmstrip = true,
                    keepScreenOn = false,
                    slideshowShuffle = false,
                    loopAnimations = false,
                    autoplayVideos = true,
                    onSetSlideshowInterval = {},
                    onSetBlurSensitive = {},
                    onSetShowFilmstrip = {},
                    onSetKeepScreenOn = {},
                    onSetSlideshowShuffle = {},
                    onSetLoopAnimations = {},
                    onSetAutoplayVideos = {},
                    transparencyBackground = mode,
                    onSetTransparencyBackground = { mode = it },
                )
            }
        }
        compose.onNodeWithTag(TRANSPARENCY_ROW_VALUE_TAG, useUnmergedTree = true).assertTextEquals("Grid")

        compose.onNodeWithText("Transparent photos").performClick()
        compose.waitForIdle()
        assertEquals(TransparencyBackground.WHITE, mode)
        compose.onNodeWithTag(TRANSPARENCY_ROW_VALUE_TAG, useUnmergedTree = true).assertTextEquals("White")

        compose.onNodeWithText("Transparent photos").performClick()
        compose.waitForIdle()
        assertEquals(TransparencyBackground.BLACK, mode)
        compose.onNodeWithTag(TRANSPARENCY_ROW_VALUE_TAG, useUnmergedTree = true).assertTextEquals("Black")
    }
}
