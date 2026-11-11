package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.GalleryShortcutKey
import com.fotoxplorr.app.adaptive.galleryShortcutFor
import com.fotoxplorr.app.composite.CompositeCopy
import com.fotoxplorr.app.composite.stackOf
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.formats.composite.StackBadge
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard and mouse paths into a burst stack on a grid too dense for the badge to be a
 * button: Shift+Enter on the focused tile, and the tile's right-click menu. Driven through the real
 * [MediaGridScreen] (ten columns on a 440 dp window: tiles under 48 dp) and a recording bridge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class BurstStackKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    private val bridge = GridChromeBridge()
    private val actions = mutableListOf<Pair<Long, MediaContextAction>>()
    private val assets = (1L..4L).map(::asset)
    private val closed = StackBadge(MediaId(1), 3, expanded = false, members = listOf(MediaId(1), MediaId(5), MediaId(6)))

    private fun host(badges: Map<MediaId, StackBadge<MediaId>>) {
        bridge.stackBadges = badges
        bridge.onContextAction = { asset, action -> actions += asset.id.value to action }
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize()) {
                        MediaGridScreen(
                            assets = assets,
                            columns = 10,
                            favoriteIds = emptySet(),
                            sensitiveIds = emptySet(),
                            blurSensitive = false,
                            selectedIds = emptySet(),
                            emptyMessage = "none",
                            selectionActive = false,
                            onOpen = {},
                            onToggleSelection = {},
                        )
                    }
                }
            }
        }
    }

    private fun tile(id: Long) = compose.onNode(hasContentDescription("IMG_$id.jpg") and hasClickAction())

    @Test
    fun `Shift+Enter toggles the focused stack and Enter still opens the photo`() {
        assertEquals(GalleryShortcut.ToggleFocusedStack, galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = false, shiftPressed = true))
        assertEquals(GalleryShortcut.OpenFocused, galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = false))
        assertEquals(GalleryShortcut.OpenFocused, galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = true, shiftPressed = false))
        // Shift changes nothing else: Shift+Delete still trashes, Shift+/ still searches.
        assertEquals(GalleryShortcut.TrashSelected, galleryShortcutFor(GalleryShortcutKey.DELETE, ctrlPressed = false, shiftPressed = true))
    }

    @Test
    fun `the stack a key or menu acts on is the cover's, or the open stack a frame belongs to`() {
        val open = closed.copy(expanded = true)
        assertEquals(MediaId(1), stackOf(mapOf(MediaId(1) to closed), MediaId(1)))
        assertNull(stackOf(mapOf(MediaId(1) to closed), MediaId(5)))
        assertEquals(MediaId(1), stackOf(mapOf(MediaId(1) to open), MediaId(6)))
        assertNull(stackOf(mapOf(MediaId(1) to open), MediaId(9)))
        assertNull(stackOf(mapOf(MediaId(1) to open), null))
    }

    @Test
    fun `a right click on a tiny cover offers Open stack`() {
        host(mapOf(MediaId(1) to closed))
        tile(1).performMouseInput { rightClick() }
        compose.onNodeWithTag(STACK_MENU_TAG).performClick()
        assertEquals(listOf(1L to MediaContextAction.TOGGLE_STACK), actions)
    }

    @Test
    fun `an open stack's frame offers Close stack and a lone photo offers neither`() {
        host(mapOf(MediaId(1) to closed.copy(members = listOf(MediaId(1), MediaId(2), MediaId(3)), expanded = true)))
        tile(2).performMouseInput { rightClick() }
        compose.onNodeWithText(CompositeCopy.MENU_CLOSE_STACK).performClick()
        assertEquals(listOf(2L to MediaContextAction.TOGGLE_STACK), actions)

        tile(4).performMouseInput { rightClick() }
        compose.onNodeWithText("Open").assertExists()
        compose.onNodeWithTag(STACK_MENU_TAG).assertDoesNotExist()
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
