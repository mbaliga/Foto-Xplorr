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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.fotoxplorr.app.GALLERY_STATE_KEY
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer round trip: the activity composes the viewer in the gallery's place, so opening a
 * photo and pressing Back used to rebuild the browser from nothing (default destination, no
 * search, top of the grid). The activity now holds the gallery under [GALLERY_STATE_KEY]; this is
 * that wiring, and the browser's first-composition reset that would otherwise wipe what comes back.
 * The search text stands in for all the rest: it is the state a user can seed from outside and the
 * one the reset effect clears.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryViewerRoundTripTest {

    @get:Rule
    val compose = createComposeRule()

    private class Host(val hold: Boolean) {
        var viewerOpen by mutableStateOf(false)
        var pendingSearch by mutableStateOf<String?>(SEARCH)
        var composedGalleries = 0
    }

    private fun mount(host: Host) {
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            FotoXplorrTheme(emptyState().preferences) {
                if (host.viewerOpen) {
                    Box(Modifier.fillMaxSize()) { Text(VIEWER_TEXT) }
                } else if (host.hold) {
                    holder.SaveableStateProvider(GALLERY_STATE_KEY) { Gallery(host) }
                } else {
                    Gallery(host)
                }
            }
        }
    }

    @Composable
    private fun Gallery(host: Host) {
        GalleryScreen(
            state = emptyState().copy(assets = assets(), pendingSearch = host.pendingSearch),
            actions = noOpActions().copy(onPendingSearchConsumed = { host.pendingSearch = null }),
        )
    }

    private fun searchShown() = compose.onAllNodesWithText(SEARCH).fetchSemanticsNodes().size

    @Test
    fun `a search survives opening the viewer and coming back when the gallery is held`() {
        val host = Host(hold = true)
        mount(host)
        compose.waitForIdle()
        assertEquals("the seeded search is on screen", 1, searchShown())

        host.viewerOpen = true
        compose.waitForIdle()
        compose.onNodeWithText(VIEWER_TEXT)
        assertEquals("the viewer replaced the gallery", 0, searchShown())

        host.viewerOpen = false
        compose.waitForIdle()
        assertEquals("Back from the viewer finds the search as it was left", 1, searchShown())
    }

    @Test
    fun `without the holder the round trip drops the search`() {
        val host = Host(hold = false)
        mount(host)
        compose.waitForIdle()
        assertEquals(1, searchShown())

        host.viewerOpen = true
        compose.waitForIdle()
        host.viewerOpen = false
        compose.waitForIdle()
        assertEquals("the control: an unheld gallery is rebuilt empty", 0, searchShown())
    }

    private fun assets(): List<MediaAsset> = (1L..6L).map { id ->
        val taken = 1_756_684_800_000L - id * 86_400_000L
        MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/images/media/$id",
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1_000L,
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private companion object {
        const val SEARCH = "beach"
        const val VIEWER_TEXT = "viewer stand-in"
    }
}
