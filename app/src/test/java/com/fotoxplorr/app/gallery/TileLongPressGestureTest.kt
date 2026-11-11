package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.up
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
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
 * What a tap and a hold on a grid tile do, driven with synthetic touches through the real
 * [MediaGridScreen] and a recording [GridChromeBridge].
 *
 * This proves the wiring -- the setting is read when the hold completes, a selection overrides it,
 * a tap uses the CURRENT open callback, a screen reader gets the same actions -- not the feel of the
 * timeout or the haptic on a real thumb, which is device-checked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class TileLongPressGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val bridge = GridChromeBridge()
    private val contextActions = mutableListOf<Pair<Long, MediaContextAction>>()
    private val opened = mutableListOf<Pair<String, Long>>()
    private val toggled = mutableListOf<Long>()
    private var selecting by mutableStateOf(false)
    private var openLabel by mutableStateOf("first")

    private val assets = (1L..3L).map(::asset)

    private fun host(setting: GridLongPress, selectionActive: Boolean = false) {
        contextActions.clear()
        opened.clear()
        toggled.clear()
        bridge.gridLongPress = setting
        bridge.onContextAction = { asset, action -> contextActions += asset.id.value to action }
        selecting = selectionActive
        openLabel = "first"
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize()) {
                        val label = openLabel
                        MediaGridScreen(
                            assets = assets,
                            columns = 3,
                            favoriteIds = emptySet(),
                            sensitiveIds = emptySet(),
                            blurSensitive = false,
                            selectedIds = emptySet(),
                            emptyMessage = "none",
                            selectionActive = selecting,
                            onOpen = { opened += label to it.id.value },
                            onToggleSelection = { toggled += it.value },
                        )
                    }
                }
            }
        }
    }

    // The click-action filter matters while a preview is up: its large copy of the photo carries the
    // same description.
    private fun tile(id: Long): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription("IMG_$id.jpg") and hasClickAction())

    private fun SemanticsNodeInteraction.hold() = performTouchInput { longClick() }

    @Test
    fun `a tap opens the photo`() {
        host(GridLongPress.PEEK)
        tile(2).performClick()
        assertEquals(listOf("first" to 2L), opened)
    }

    @Test
    fun `a tap uses the open callback of the latest composition`() {
        host(GridLongPress.PEEK)
        openLabel = "second"
        compose.waitForIdle()
        tile(1).performClick()
        assertEquals(listOf("second" to 1L), opened)
    }

    @Test
    fun `a tap toggles the photo while a selection is running`() {
        host(GridLongPress.PEEK, selectionActive = true)
        tile(3).performClick()
        assertEquals(listOf(3L), toggled)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `holding with the preview setting shows the photo large until release`() {
        host(GridLongPress.PEEK)
        tile(1).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Release to go back").assertIsDisplayed()
        tile(1).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("Release to go back").assertDoesNotExist()
        assertTrue(toggled.isEmpty() && contextActions.isEmpty() && opened.isEmpty())
    }

    @Test
    fun `holding with select toggles the photo`() {
        host(GridLongPress.SELECT)
        tile(2).hold()
        assertEquals(listOf(2L), toggled)
        assertTrue(contextActions.isEmpty())
        compose.onNodeWithText("Release to go back").assertDoesNotExist()
    }

    private fun holdAsksFor(setting: GridLongPress, action: MediaContextAction) {
        host(setting)
        tile(3).hold()
        assertEquals(listOf(3L to action), contextActions)
        assertTrue(toggled.isEmpty() && opened.isEmpty())
    }

    @Test
    fun `holding with favourite asks the owner to toggle the favourite`() =
        holdAsksFor(GridLongPress.FAVOURITE, MediaContextAction.TOGGLE_FAVORITE)

    @Test
    fun `holding with share asks the owner to share the photo`() =
        holdAsksFor(GridLongPress.SHARE, MediaContextAction.SHARE)

    @Test
    fun `holding with edit asks the owner to edit the photo`() =
        holdAsksFor(GridLongPress.EDIT, MediaContextAction.EDIT)

    @Test
    fun `holding with nothing does nothing`() {
        host(GridLongPress.NOTHING)
        tile(1).hold()
        assertTrue(toggled.isEmpty() && contextActions.isEmpty() && opened.isEmpty())
        compose.onNodeWithText("Release to go back").assertDoesNotExist()
    }

    @Test
    fun `while selecting a hold toggles the photo whatever the setting`() {
        host(GridLongPress.EDIT, selectionActive = true)
        tile(2).hold()
        assertEquals(listOf(2L), toggled)
        assertTrue(contextActions.isEmpty())
    }

    @Test
    fun `while selecting a hold set to nothing stays inert`() {
        host(GridLongPress.NOTHING, selectionActive = true)
        tile(2).hold()
        assertTrue(toggled.isEmpty() && contextActions.isEmpty())
    }

    @Test
    fun `the setting is read when the hold completes so a change applies without recomposing the tile`() {
        host(GridLongPress.SELECT)
        tile(1).hold()
        assertEquals(listOf(1L), toggled)
        bridge.gridLongPress = GridLongPress.EDIT
        compose.waitForIdle()
        tile(1).hold()
        assertEquals(listOf(1L to MediaContextAction.EDIT), contextActions)
    }

    @Test
    fun `a tile exposes click and long click to a screen reader under the configured names`() {
        host(GridLongPress.EDIT)
        val config = tile(1).fetchSemanticsNode().config
        assertEquals("Open", config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals("Edit", config.getOrNull(SemanticsActions.OnLongClick)?.label)
    }

    @Test
    fun `a screen reader's long click runs the same action as a hold`() {
        host(GridLongPress.SHARE)
        tile(2).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(2L to MediaContextAction.SHARE), contextActions)
    }

    @Test
    fun `a screen reader's long click selects under the preview setting`() {
        host(GridLongPress.PEEK)
        val config = tile(2).fetchSemanticsNode().config
        assertEquals("Select", config.getOrNull(SemanticsActions.OnLongClick)?.label)
        tile(2).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(2L), toggled)
    }

    @Test
    fun `a screen reader is offered no long click when a hold does nothing`() {
        host(GridLongPress.NOTHING)
        assertNull(tile(1).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick))
    }

    @Test
    fun `the long click label follows a setting change`() {
        host(GridLongPress.FAVOURITE)
        assertEquals("Favourite", tile(1).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)?.label)
        bridge.gridLongPress = GridLongPress.SHARE
        compose.waitForIdle()
        assertEquals("Share", tile(1).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)?.label)
    }

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        bucketId = 100L,
        dateTakenMillis = 1_756_684_800_000L - id * 86_400_000L,
        dateModifiedSeconds = 1_756_684_800L,
        width = 4032,
        height = 3024,
        sizeBytes = 3_200_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )
}
