package com.fotoxplorr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryActionsRoom
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySelection
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.viewer.ViewerActionsRoom
import dev.aarso.cellshell.RoomStage
import dev.aarso.cellshell.SpatialController
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The actions room's two stages through the real shell: a short pull released at the peek shows
 * the quick-action strip and nothing else of the room, a longer pull opens all of it, and a quick
 * action performs and closes.
 *
 * "Nothing else" is measured, not assumed. The peek is counted by the shell from the parked card's
 * lower edge while the room is inset by the shell's own band beneath that edge, so a peek that was
 * only as tall as the strip would reveal an empty inset ([RoomGeography.CardBand] is the number
 * that fixes it). These tests look at where the strip and the first full row actually land, which
 * is what fails if the band, the strip's height or the shell's geometry drift apart.
 *
 * Insets are zero under Robolectric, so the navigation bar's share of the peek is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ActionsPeekTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: SpatialController
    private var shared = 0

    private fun showViewerRoom() = showShell {
        ViewerActionsRoom(
            isFavorite = false,
            isSensitive = false,
            canMoveToTrash = true,
            slideshowActive = false,
            onToggleSlideshow = {},
            onToggleFavorite = {},
            onToggleSensitive = {},
            onShare = { controller.closeAll(); shared++ },
            onEdit = {},
            onOpenWith = {},
            onMoveToTrash = {},
            onCloseRoom = { controller.closeAll() },
        )
    }

    private fun showGalleryRoom() = showShell {
        GalleryActionsRoom(
            state = emptyState(),
            actions = noOpActions(),
            selection = GallerySelection(),
            selectedAssets = emptyList(),
            currentIds = emptySet(),
            inTrash = false,
            inArchive = false,
            tagRoute = null,
            collectionRoute = null,
            onSelectionChange = {},
            onRenameAsset = {},
            onAddToCollection = {},
            onAddTag = {},
            onStartSelection = { controller.closeAll() },
            onNewCollection = { controller.closeAll() },
            onCloseRoom = { controller.closeAll() },
        )
    }

    private fun showShell(room: @Composable () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                controller = rememberSpatialController()
                SpatialShell(
                    controller = controller,
                    accentColor = Color.White,
                    scrimColor = Color.Black,
                    cardColor = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    bottom = room,
                    bottomPeek = RoomGeography.peekFor(RoomKind.ACTIONS),
                ) { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
            }
        }
        compose.waitForIdle()
    }

    /**
     * A pull so slow that no frame counts as a flick (under 2px a frame), so the release settles on
     * the nearest stop. 1,100px over 10s is 1.1px per 16ms frame.
     */
    private fun slowPullUp(px: Float) {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 20f),
                end = Offset(centerX, bottom - 20f - px),
                durationMillis = 10_000,
            )
        }
        compose.waitForIdle()
    }

    private fun rootHeightPx() = compose.onRoot().fetchSemanticsNode().size.height

    private fun stripBounds() = compose.onNodeWithTag(QUICK_ACTION_STRIP_TAG).fetchSemanticsNode().boundsInRoot

    private fun assertOnlyTheStripShows(firstFullRow: String) {
        val screen = rootHeightPx().toFloat()
        val strip = stripBounds()
        assertTrue("the strip is cut off: bottom ${strip.bottom} of $screen", strip.bottom <= screen + 1f)
        // Flush with the foot of the screen, give or take the few dp the card's scaling leaves.
        val slack = with(compose.density) { 16.dp.toPx() }
        assertTrue("the strip floats ${screen - strip.bottom}px above the foot", screen - strip.bottom <= slack)
        val row = compose.onNodeWithText(firstFullRow).fetchSemanticsNode().boundsInRoot
        assertTrue("$firstFullRow is on screen at the peek (top ${row.top} of $screen)", row.top >= screen)
    }

    @Test
    fun `a short release in the viewer settles on the peek and shows only the strip`() {
        showViewerRoom()
        slowPullUp(px = 300f)
        assertEquals(RoomStage.PEEK, controller.bottomStage)
        assertOnlyTheStripShows(firstFullRow = "Slideshow")
    }

    @Test
    fun `a short release in the gallery settles on the peek and shows only the strip`() {
        showGalleryRoom()
        slowPullUp(px = 300f)
        assertEquals(RoomStage.PEEK, controller.bottomStage)
        assertOnlyTheStripShows(firstFullRow = "ORGANISE")
    }

    @Test
    fun `peeking without a gesture lands on the same strip`() {
        showViewerRoom()
        compose.runOnIdle { controller.peekBottom() }
        compose.waitForIdle()
        assertEquals(RoomStage.PEEK, controller.bottomStage)
        assertOnlyTheStripShows(firstFullRow = "Slideshow")
    }

    @Test
    fun `a long pull opens the whole room with the strip still at its head`() {
        showViewerRoom()
        slowPullUp(px = 1_100f)
        assertEquals(RoomStage.FULL, controller.bottomStage)
        val screen = rootHeightPx().toFloat()
        val row = compose.onNodeWithText("Slideshow").fetchSemanticsNode().boundsInRoot
        assertTrue("Slideshow is not on screen at full", row.bottom <= screen)
        assertTrue("the strip is below the list", stripBounds().bottom <= row.top + 1f)
    }

    @Test
    fun `a quick action in the peek performs and closes the room`() {
        showViewerRoom()
        compose.runOnIdle { controller.peekBottom() }
        compose.waitForIdle()
        compose.onNode(hasText("Share") and hasAnyAncestor(hasTestTag(QUICK_ACTION_STRIP_TAG))).performClick()
        compose.waitForIdle()
        assertEquals(1, shared)
        assertEquals(RoomStage.CLOSED, controller.bottomStage)
    }

    @Test
    fun `a closed room shows no strip on screen`() {
        showViewerRoom()
        compose.onAllNodesWithText("Share").also { assertEquals(0, it.fetchSemanticsNodes().size) }
    }
}
