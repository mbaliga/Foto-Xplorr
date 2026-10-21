package com.fotoxplorr.app.gallery

import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.ACTIONS_PEEK_NAV_SPACER_TAG
import com.fotoxplorr.app.ui.QUICK_ACTION_STRIP_TAG
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gallery's quick-action strip follows the selection: with photos chosen its cells are the
 * selection's actions, run through the same finish step the full list uses (act, clear the
 * selection, close the room), and the strip's Refresh and the list's "Rescan the library" are one
 * handler. Pure decisions first, then the real room.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryQuickStripSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val id = MediaId(1L)

    // ---- the pure decision ----

    @Test
    fun `an empty selection that has just begun answers to nothing`() {
        listOf(
            GalleryQuickAction.SHARE,
            GalleryQuickAction.ADD_TO_COLLECTION,
            GalleryQuickAction.FAVOURITE,
            GalleryQuickAction.MOVE_TO_TRASH,
            GalleryQuickAction.RESTORE,
            GalleryQuickAction.DELETE_PERMANENTLY,
        ).forEach {
            assertFalse("$it with nothing chosen", galleryQuickActionEnabled(it, 0, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
            assertTrue("$it with one chosen", galleryQuickActionEnabled(it, 1, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        }
    }

    @Test
    fun `trash restore and delete need Android 11 as their rows do`() {
        listOf(GalleryQuickAction.MOVE_TO_TRASH, GalleryQuickAction.RESTORE, GalleryQuickAction.DELETE_PERMANENTLY)
            .forEach {
                assertFalse("$it on Android 10", galleryQuickActionEnabled(it, 3, Build.VERSION_CODES.Q))
                assertTrue("$it on Android 11", galleryQuickActionEnabled(it, 3, Build.VERSION_CODES.R))
            }
        // Share, collection and favourite do not.
        assertTrue(galleryQuickActionEnabled(GalleryQuickAction.SHARE, 3, Build.VERSION_CODES.Q))
    }

    @Test
    fun `the browsing cells always answer`() {
        listOf(GalleryQuickAction.SELECT, GalleryQuickAction.NEW_COLLECTION, GalleryQuickAction.REFRESH)
            .forEach { assertTrue(galleryQuickActionEnabled(it, 0, Build.VERSION_CODES.Q)) }
    }

    // ---- the real room ----

    private class Tally {
        var shared: List<MediaAsset>? = null
        var trashed: List<MediaAsset>? = null
        var favourite: Pair<Set<MediaId>, Boolean>? = null
        var refreshed = 0
        var roomsClosed = 0
        var collectionFor: Set<MediaId>? = null
        var selection: GallerySelection? = null
    }

    private fun show(
        selection: GallerySelection,
        tally: Tally,
        assets: List<MediaAsset> = emptyList(),
        state: GalleryUiState = emptyState(),
        hasBottomPeek: Boolean = true,
    ) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                GalleryActionsRoom(
                    state = state,
                    actions = noOpActions().copy(
                        onShare = { tally.shared = it },
                        onMoveToTrash = { tally.trashed = it },
                        onSetFavorite = { ids, mark -> tally.favourite = ids to mark },
                        onRefresh = { tally.refreshed++ },
                    ),
                    selection = selection,
                    selectedAssets = assets,
                    currentIds = emptySet(),
                    inTrash = false,
                    inArchive = false,
                    tagRoute = null,
                    collectionRoute = null,
                    onSelectionChange = { tally.selection = it },
                    onRenameAsset = {},
                    onAddToCollection = { tally.collectionFor = it },
                    onAddTag = {},
                    onStartSelection = {},
                    onNewCollection = {},
                    onCloseRoom = { tally.roomsClosed++ },
                    hasBottomPeek = hasBottomPeek,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun strip(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(QUICK_ACTION_STRIP_TAG)))

    @Test
    fun `with photos chosen the strip shows the selection actions and not refresh`() {
        show(GallerySelection(selectedIds = setOf(id), selecting = true), Tally())
        listOf("Share", "Collection", "Favourite", "Trash").forEach { strip(it).assertExists() }
        listOf("Select", "New collection", "Refresh").forEach { strip(it).assertDoesNotExist() }
    }

    @Test
    fun `a strip cell runs the selection action, clears the selection and closes the room`() {
        val tally = Tally()
        val selection = GallerySelection(selectedIds = setOf(id), selecting = true)
        show(selection, tally)
        strip("Favourite").performClick()
        assertEquals(setOf(id) to true, tally.favourite)
        assertEquals(GallerySelection(), tally.selection)
        assertEquals(1, tally.roomsClosed)
    }

    @Test
    fun `the add to collection cell hands over the chosen photos and closes the room`() {
        val tally = Tally()
        show(GallerySelection(selectedIds = setOf(id), selecting = true), tally)
        strip("Collection").performClick()
        assertEquals(setOf(id), tally.collectionFor)
        assertEquals(1, tally.roomsClosed)
    }

    @Test
    fun `when everything chosen is already a favourite the cell says unfavourite and removes them`() {
        val tally = Tally()
        show(
            GallerySelection(selectedIds = setOf(id), selecting = true),
            tally,
            state = emptyState().copy(favoriteIds = setOf(id)),
        )
        strip("Favourite").assertDoesNotExist()
        strip("Unfavourite").performClick()
        assertEquals(setOf(id) to false, tally.favourite)
    }

    @Test
    fun `without a bottom peek the room draws no strip but still lists its actions`() {
        show(GallerySelection(selectedIds = setOf(id), selecting = true), Tally(), hasBottomPeek = false)
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).assertCountEquals(0)
        compose.onNodeWithText("Select all").assertExists()
    }

    @Test
    fun `a selection with nothing chosen yet leaves the cells dead`() {
        val tally = Tally()
        show(GallerySelection(selecting = true), tally)
        strip("Share").performClick()
        assertEquals(null, tally.shared)
        assertEquals(0, tally.roomsClosed)
    }

    @Test
    fun `the strip's refresh and the list's rescan are the same handler`() {
        val fromStrip = Tally()
        show(GallerySelection(), fromStrip)
        strip("Refresh").performClick()
        assertEquals(1, fromStrip.refreshed)
        assertEquals(1, fromStrip.roomsClosed)
    }

    @Test
    fun `rescanning from the full list closes the room too`() {
        val tally = Tally()
        show(GallerySelection(), tally)
        compose.onNodeWithText("Rescan the library").performScrollTo().performClick()
        assertEquals(1, tally.refreshed)
        assertEquals("the list's rescan left the room open", 1, tally.roomsClosed)
    }
}
