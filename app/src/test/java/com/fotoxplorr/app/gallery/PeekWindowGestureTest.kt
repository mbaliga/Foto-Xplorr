package com.fotoxplorr.app.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.noOpActions
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
 * The two preview modes, driven with synthetic touches through the real [MediaGridScreen], a
 * [GridChromeBridge] and the real [PeekWindow] -- hosted the way `GalleryScreen` hosts them: the
 * bridge's [GridChromeBridge.onOpenPeekWindow] puts the photo into the screen's state, the window
 * is drawn above the grid from that state, and its actions go through [runPeekAction] into a
 * [GalleryActions].
 *
 * This proves the wiring (which mode draws what, what survives a release, what closes the window
 * and what each glyph calls), not the feel on a real thumb or what the chrome looks like on a
 * device; see `PeekWindowRenderTest` for the geometry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PeekWindowGestureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val bridge = GridChromeBridge()
    private val opened = mutableListOf<Long>()
    private val toggled = mutableListOf<Long>()
    private val ran = mutableListOf<Pair<String, List<Long>>>()
    private var window by mutableStateOf<MediaAsset?>(null)

    private val assets = listOf(asset(1), asset(2), asset(3, source = true))

    private val actions = noOpActions().copy(
        onExportZip = { ran += "zip" to it.map { a -> a.id.value } },
        onMoveToFolder = { ran += "move" to it.map { a -> a.id.value } },
        onCopyToFolder = { ran += "copy" to it.map { a -> a.id.value } },
    )

    private fun host(mode: PeekMode, inTrash: Boolean = false, shown: List<MediaAsset> = assets) {
        bridge.gridLongPress = GridLongPress.PEEK
        bridge.peekMode = mode
        bridge.onOpenPeekWindow = { asset ->
            val ok = shown.any { it.id == asset.id }
            if (ok) window = asset
            ok
        }
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize()) {
                        MediaGridScreen(
                            assets = assets,
                            columns = 3,
                            favoriteIds = emptySet(),
                            sensitiveIds = emptySet(),
                            blurSensitive = false,
                            selectedIds = emptySet(),
                            emptyMessage = "none",
                            onOpen = { opened += it.id.value },
                            onToggleSelection = { toggled += it.value },
                        )
                        window?.let { peeked ->
                            PeekWindow(
                                asset = peeked,
                                blurred = false,
                                actions = peekActionsFor(peeked, inTrash),
                                animate = false,
                                onAction = { runPeekAction(it, peeked, actions) },
                                onClose = { window = null },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tile(id: Long): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription("IMG_$id.jpg") and hasClickAction())

    private fun holdAndRelease(id: Long) {
        tile(id).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        tile(id).performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun assertNoChrome() {
        compose.onNodeWithTag(PEEK_WINDOW_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PEEK_CHROME_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PEEK_CLOSE_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(PeekCopy.CLOSE).assertDoesNotExist()
        compose.onNodeWithContentDescription(PeekCopy.EXPORT_ZIP).assertDoesNotExist()
    }

    @Test
    fun `close on release still holds the plain preview and shows no chrome`() {
        host(PeekMode.CLOSE_ON_RELEASE)
        tile(1).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(PeekCopy.RELEASE_HINT).assertIsDisplayed()
        assertNoChrome()
        tile(1).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText(PeekCopy.RELEASE_HINT).assertDoesNotExist()
        assertNoChrome()
        assertNull(window)
        assertTrue(opened.isEmpty() && toggled.isEmpty())
    }

    @Test
    fun `stays open shows the window with its chrome while held and after release`() {
        host(PeekMode.STAYS_OPEN)
        tile(2).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag(PEEK_WINDOW_TAG).assertIsDisplayed()
        compose.onNodeWithText(PeekCopy.RELEASE_HINT).assertDoesNotExist()
        tile(2).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag(PEEK_WINDOW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PEEK_CHROME_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PEEK_CLOSE_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(PeekCopy.EXPORT_ZIP).assertIsDisplayed()
        compose.onNodeWithContentDescription(PeekCopy.MOVE_TO_FOLDER).assertIsDisplayed()
        compose.onNodeWithContentDescription(PeekCopy.COPY_TO_FOLDER).assertIsDisplayed()
        assertEquals(2L, window?.id?.value)
        // The release after the hold is not a tap: nothing opened, nothing selected.
        assertTrue(opened.isEmpty() && toggled.isEmpty())
    }

    @Test
    fun `the setting switches modes without recomposing the tile`() {
        host(PeekMode.CLOSE_ON_RELEASE)
        holdAndRelease(1)
        assertNull(window)
        bridge.peekMode = PeekMode.STAYS_OPEN
        compose.waitForIdle()
        holdAndRelease(1)
        compose.onNodeWithTag(PEEK_WINDOW_TAG).assertIsDisplayed()
    }

    @Test
    fun `the cross closes the window`() {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(1)
        compose.onNodeWithTag(PEEK_CLOSE_TAG).performClick()
        compose.waitForIdle()
        assertNull(window)
        assertNoChrome()
        assertTrue(opened.isEmpty() && ran.isEmpty())
    }

    @Test
    fun `back closes the window`() {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(1)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(window)
        assertNoChrome()
    }

    @Test
    fun `a tap on the backdrop closes the window and reaches no tile`() {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(1)
        // Top left of the screen: outside the centred card, over the first tile.
        compose.onNodeWithTag(PEEK_BACKDROP_TAG).performTouchInput { click(Offset(20f, 20f)) }
        compose.waitForIdle()
        assertNull(window)
        assertTrue(opened.isEmpty() && toggled.isEmpty())
    }

    @Test
    fun `a tap on the photo in the window does nothing`() {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(1)
        compose.onNodeWithTag(PEEK_WINDOW_TAG).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1L, window?.id?.value)
        assertTrue(opened.isEmpty() && toggled.isEmpty() && ran.isEmpty())
    }

    private fun glyphRuns(description: String, expected: String) {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(2)
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
        assertEquals(listOf(expected to listOf(2L)), ran)
        assertNull(window)
    }

    @Test
    fun `the zip glyph exports the previewed photo and closes`() = glyphRuns(PeekCopy.EXPORT_ZIP, "zip")

    @Test
    fun `the move glyph moves the previewed photo and closes`() = glyphRuns(PeekCopy.MOVE_TO_FOLDER, "move")

    @Test
    fun `the copy glyph copies the previewed photo and closes`() = glyphRuns(PeekCopy.COPY_TO_FOLDER, "copy")

    @Test
    fun `a source file gets no move glyph`() {
        host(PeekMode.STAYS_OPEN)
        holdAndRelease(3)
        compose.onNodeWithContentDescription(PeekCopy.MOVE_TO_FOLDER).assertDoesNotExist()
        compose.onNodeWithContentDescription(PeekCopy.EXPORT_ZIP).assertIsDisplayed()
        compose.onNodeWithContentDescription(PeekCopy.COPY_TO_FOLDER).performClick()
        compose.waitForIdle()
        assertEquals(listOf("copy" to listOf(3L)), ran)
    }

    @Test
    fun `the trash shows the window with no glyphs`() {
        host(PeekMode.STAYS_OPEN, inTrash = true)
        holdAndRelease(1)
        compose.onNodeWithTag(PEEK_CLOSE_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(PeekCopy.EXPORT_ZIP).assertDoesNotExist()
        compose.onNodeWithContentDescription(PeekCopy.COPY_TO_FOLDER).assertDoesNotExist()
    }

    @Test
    fun `a photo the screen does not show falls back to the held preview`() {
        host(PeekMode.STAYS_OPEN, shown = emptyList())
        tile(1).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText(PeekCopy.RELEASE_HINT).assertIsDisplayed()
        assertNoChrome()
        tile(1).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText(PeekCopy.RELEASE_HINT).assertDoesNotExist()
    }

    @Test
    fun `a screen reader's long click opens the window when it stays open`() {
        host(PeekMode.STAYS_OPEN)
        val config = tile(2).fetchSemanticsNode().config
        assertEquals(GridLongPress.PEEK.label, config.getOrNull(SemanticsActions.OnLongClick)?.label)
        tile(2).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(2L, window?.id?.value)
        assertTrue(toggled.isEmpty())
    }

    @Test
    fun `a screen reader's long click still selects when the preview closes on release`() {
        host(PeekMode.CLOSE_ON_RELEASE)
        assertEquals("Select", tile(2).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)?.label)
    }

    @Test
    fun `a plain hold in stays open mode is still one tap target per tile`() {
        host(PeekMode.STAYS_OPEN)
        tile(1).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(PEEK_WINDOW_TAG).assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    private fun asset(id: Long, source: Boolean = false) = MediaAsset(
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
        sourceRef = if (source) "src:1:$id" else null,
    )
}
