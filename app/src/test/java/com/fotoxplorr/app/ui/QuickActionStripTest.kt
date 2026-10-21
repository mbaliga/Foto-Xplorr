package com.fotoxplorr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GalleryQuickAction
import com.fotoxplorr.app.gallery.galleryQuickActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The quick-action strip: icons with short labels that are real, big-enough buttons, that do the
 * thing when tapped, and that draw nothing at all when there is nothing to offer. Plus the
 * gallery's rule for which of its three are allowed (the viewer's is in
 * `ViewerRoomGeographyTest`, next to the screen that applies it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class QuickActionStripTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(items: List<QuickActionItem>, width: androidx.compose.ui.unit.Dp = 360.dp) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    QuickActionStrip(items, Modifier.width(width))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun item(label: String, enabled: Boolean = true, onClick: () -> Unit = {}) =
        QuickActionItem(label, Icons.Outlined.Share, onClick, enabled)

    @Test
    fun `each action shows its short label`() {
        show(listOf(item("Favourite"), item("Share"), item("Edit"), item("Trash")))
        listOf("Favourite", "Share", "Edit", "Trash").forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `every cell is at least 48dp either way even with four across a narrow phone`() {
        show(listOf(item("Favourite"), item("Share"), item("Edit"), item("Trash")), width = 320.dp)
        val density = compose.density
        val minPx = with(density) { QuickActionMinTarget.toPx() }
        listOf("Favourite", "Share", "Edit", "Trash").forEach { label ->
            // The label is merged into its cell, so the clickable node's size is the target's.
            val cell = compose.onNodeWithText(label).fetchSemanticsNode()
            assertTrue("$label is ${cell.size.width}px wide", cell.size.width >= minPx)
            assertTrue("$label is ${cell.size.height}px tall", cell.size.height >= minPx)
        }
    }

    @Test
    fun `the strip is exactly the peek height tall`() {
        show(listOf(item("Share")))
        // The shell is told to stop the pull at this much room, so the strip must fill it exactly:
        // shorter leaves a gap of room under it, taller hides its top behind the card.
        val strip = compose.onNodeWithTag(QUICK_ACTION_STRIP_TAG).fetchSemanticsNode()
        assertEquals(with(compose.density) { QuickActionStripHeight.roundToPx() }, strip.size.height)
    }

    @Test
    fun `tapping a cell performs its action once`() {
        var taps = 0
        show(listOf(item("Share", onClick = { taps++ }), item("Edit")))
        compose.onNodeWithText("Share").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a disabled cell is disabled and does not answer`() {
        var taps = 0
        show(listOf(item("Trash", enabled = false, onClick = { taps++ }), item("Share")))
        compose.onNodeWithText("Trash").assertIsNotEnabled()
        compose.onNodeWithText("Share").assertIsEnabled()
        compose.onNodeWithText("Trash").performClick()
        assertEquals(0, taps)
    }

    @Test
    fun `an empty strip draws nothing`() {
        show(emptyList())
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(0)
    }

    @Test
    fun `the gallery offers select new collection and refresh when nothing is selected`() {
        assertEquals(
            listOf(GalleryQuickAction.SELECT, GalleryQuickAction.NEW_COLLECTION, GalleryQuickAction.REFRESH),
            galleryQuickActions(selectionActive = false),
        )
    }

    @Test
    fun `during a selection the strip becomes the selection's own actions`() {
        // Starting a selection is no longer an action, and New collection and Refresh do not act
        // on the chosen photos, so none of the three is on offer.
        assertEquals(
            listOf(
                GalleryQuickAction.SHARE,
                GalleryQuickAction.ADD_TO_COLLECTION,
                GalleryQuickAction.FAVOURITE,
                GalleryQuickAction.MOVE_TO_TRASH,
            ),
            galleryQuickActions(selectionActive = true),
        )
    }

    @Test
    fun `during a selection in the trash the strip is restore and delete`() {
        assertEquals(
            listOf(GalleryQuickAction.RESTORE, GalleryQuickAction.DELETE_PERMANENTLY),
            galleryQuickActions(selectionActive = true, inTrash = true),
        )
    }

    @Test
    fun `the gallery labels are short and in the order the strip lays them`() {
        assertEquals(
            listOf("Select", "New collection", "Refresh"),
            galleryQuickActions(selectionActive = false).map { it.label },
        )
        assertEquals(
            listOf("Share", "Add to collection", "Favourite", "Move to trash"),
            galleryQuickActions(selectionActive = true).map { it.label },
        )
    }
}
