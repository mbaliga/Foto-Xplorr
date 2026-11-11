package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.fotoxplorr.app.GALLERY_STATE_KEY
import com.fotoxplorr.app.composite.CompositeGroupsMemory
import com.fotoxplorr.app.composite.burstBadgeTag
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Composite media through the real [GalleryScreen]: with a burst collapsed to its cover and a Live
 * Photo's movie hidden behind its still, the actions room's Select all, Move to trash and Share act
 * on what the grid shows and nothing behind it; and an open stack and the grouping come back
 * as left after the viewer round trip ([GALLERY_STATE_KEY]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryCompositeSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    private class Host {
        var viewerOpen by mutableStateOf(false)
        var trashed: List<MediaAsset>? = null
        var shared: List<MediaAsset>? = null
    }

    @Before
    fun forgetGroups() {
        CompositeGroupsMemory.last = null
    }

    private fun mount(host: Host) {
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            FotoXplorrTheme(emptyState().preferences) {
                if (host.viewerOpen) {
                    Box(Modifier.fillMaxSize()) { Text(VIEWER_TEXT) }
                } else {
                    holder.SaveableStateProvider(GALLERY_STATE_KEY) { Gallery(host) }
                }
            }
        }
        // Grouping runs off the main thread; wait for the stack to be drawn.
        compose.waitUntil(10_000) { shown(COVER) && !shown(FRAME_A) }
    }

    @Composable
    private fun Gallery(host: Host) {
        GalleryScreen(
            state = emptyState().copy(assets = library),
            actions = noOpActions().copy(
                onMoveToTrash = { host.trashed = it },
                onShare = { host.shared = it },
            ),
        )
    }

    private fun shown(id: Long): Boolean =
        compose.onAllNodes(hasContentDescription(name(id)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    /** An empty selection started from the room (which closes), then Select all from its list. */
    private fun selectAllFromTheRoom() {
        openActionsRoom()
        compose.onNodeWithText("Select photos").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        openActionsRoom()
        compose.onNodeWithText("Select all").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("${VISIBLE.size} SELECTED").assertExists()
    }

    /** A pull up from the foot of the screen, past the peek, onto the full actions room. */
    private fun openActionsRoom() {
        compose.onRoot().performTouchInput {
            swipe(start = Offset(centerX, bottom - 20f), end = Offset(centerX, bottom - 1_400f), durationMillis = 600)
        }
        compose.waitForIdle()
    }

    @Test
    fun `the actions room's Select all and Move to trash act only on the photos the grid shows`() {
        val host = Host()
        mount(host)
        selectAllFromTheRoom()
        compose.onNodeWithText("Rename ${VISIBLE.size} photos").assertExists()
        compose.onNodeWithText("Move to trash").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(VISIBLE, host.trashed!!.map { it.id.value }.toSet())
    }

    @Test
    fun `the actions room's Select all and Share act only on the photos the grid shows`() {
        val host = Host()
        mount(host)
        selectAllFromTheRoom()
        compose.onNodeWithText("Share with options…").assertExists()
        compose.onAllNodesWithText("Share").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(VISIBLE, host.shared!!.map { it.id.value }.toSet())
    }

    @Test
    fun `an open stack and the grouping come back as left after the viewer`() {
        val host = Host()
        mount(host)
        compose.onNodeWithTag(burstBadgeTag(MediaId(COVER))).performClick()
        compose.waitUntil(5_000) { shown(FRAME_A) && shown(FRAME_B) }

        host.viewerOpen = true
        compose.waitForIdle()
        compose.onNodeWithText(VIEWER_TEXT).assertExists()

        host.viewerOpen = false
        compose.waitForIdle()
        assertEquals("the open stack is still open", true, shown(FRAME_A) && shown(FRAME_B) && shown(COVER))
        assertEquals("the Live Photo's movie stays hidden", false, shown(MOVIE))
    }

    private val library: List<MediaAsset> = listOf(
        asset(LONE, "IMG_9999.jpg", minutesAgo = 0),
        // A Google Camera burst: the cover and two frames, a second apart.
        asset(COVER, "00000IMG_00000_BURST20240101101010123_COVER.jpg", minutesAgo = 10, ms = 0),
        asset(FRAME_A, "00001IMG_00001_BURST20240101101010123.jpg", minutesAgo = 10, ms = 300),
        asset(FRAME_B, "00002IMG_00002_BURST20240101101010123.jpg", minutesAgo = 10, ms = 600),
        // A Live Photo: the still and its movie, same name and moment.
        asset(STILL, "IMG_0100.HEIC", minutesAgo = 30, mime = "image/heic"),
        asset(MOVIE, "IMG_0100.MOV", minutesAgo = 30, ms = 400, mime = "video/quicktime"),
    )

    private fun name(id: Long) = library.single { it.id.value == id }.displayName

    private fun asset(id: Long, name: String, minutesAgo: Long, ms: Long = 0, mime: String = "image/jpeg"): MediaAsset {
        val taken = 1_756_684_800_000L - minutesAgo * 60_000L + ms
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/${if (mime.startsWith("video")) "video" else "images"}/media/$id",
            displayName = name,
            mimeType = mime,
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1_000L + 7,
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
            durationMillis = if (mime.startsWith("video")) 2_000L else 0L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private companion object {
        const val LONE = 1L
        const val COVER = 2L
        const val FRAME_A = 3L
        const val FRAME_B = 4L
        const val STILL = 5L
        const val MOVIE = 6L
        val VISIBLE = setOf(LONE, COVER, STILL)
        const val VIEWER_TEXT = "viewer stand-in"
    }
}
