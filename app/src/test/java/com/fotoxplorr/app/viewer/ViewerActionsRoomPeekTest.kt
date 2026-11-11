package com.fotoxplorr.app.viewer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.ACTIONS_PEEK_NAV_SPACER_TAG
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.QUICK_ACTION_STRIP_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The viewer's actions room draws its strip only where the shell has a bottom peek to stop on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerActionsRoomPeekTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(hasBottomPeek: Boolean) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                ViewerActionsRoom(
                    isFavorite = false,
                    isSensitive = false,
                    canMoveToTrash = true,
                    slideshowActive = false,
                    onToggleSlideshow = {},
                    onToggleFavorite = {},
                    onToggleSensitive = {},
                    onShare = {},
                    onEdit = {},
                    onOpenWith = {},
                    onMoveToTrash = {},
                    hasBottomPeek = hasBottomPeek,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `with a bottom peek the strip and its spacer are drawn`() {
        show(hasBottomPeek = true)
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).assertCountEquals(1)
    }

    @Test
    fun `without a bottom peek the strip and its spacer are gone and the rows remain`() {
        show(hasBottomPeek = false)
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).assertCountEquals(0)
        compose.onNodeWithText("Slideshow").assertExists()
    }
}
