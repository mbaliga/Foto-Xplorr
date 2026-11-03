package com.fotoxplorr.app.hyle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryInfoRoom
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top room's text stays clear of the timeline strip and the sheet's corner, and the photo
 * stack flips. Layout is real under native graphics; nothing here says how it LOOKS on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class RoomCornersAndStackTest {

    @get:Rule
    val compose = createComposeRule()

    private val recognising = emptyState().copy(
        recognitionProgress = RecognitionProgress(running = true, completed = 120, total = 19_951),
    )

    private fun mountRoom(gutterStart: Int, gutterEnd: Int, lift: Float) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GalleryInfoRoom(
                    title = "Photos",
                    assets = emptyList(),
                    state = recognising,
                    lift = { lift },
                    gutterStartDp = gutterStart,
                    gutterEndDp = gutterEnd,
                )
            }
        }
    }

    @Test
    fun `the count is not under a right hand timeline strip`() {
        mountRoom(gutterStart = 0, gutterEnd = 44, lift = 1f)
        val root = compose.onRoot().getBoundsInRoot()
        val count = compose.onNodeWithText("120 of 19951").getBoundsInRoot()
        assertTrue("count ends at ${count.right}", count.right <= root.right - 44.dp)
    }

    @Test
    fun `the label is not under a left hand timeline strip`() {
        mountRoom(gutterStart = 44, gutterEnd = 0, lift = 1f)
        val label = compose.onNodeWithText("Recognising").getBoundsInRoot()
        assertTrue("label starts at ${label.left}", label.left >= 44.dp)
    }

    @Test
    fun `the label clears the rounded corner when the room is open`() {
        mountRoom(gutterStart = 0, gutterEnd = 0, lift = 1f)
        val label = compose.onNodeWithText("Recognising").getBoundsInRoot()
        assertTrue(label.left >= (SheetGeometry.FULL_CORNER_DP + SheetGeometry.CORNER_MARGIN_DP).dp)
    }

    @Test
    fun `the shade rows clear the corner when the card is parked`() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalSheetLift provides { 1f }) {
                ActivityShade(
                    activities = listOf(BackgroundActivity("r", ActivityKind.RECOGNISING, 120, 19_951)),
                    state = ShadeState.NOTIFICATION,
                    onStateChange = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val label = compose.onNodeWithText("Recognising", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("label left ${label.left}", label.left >= (SheetGeometry.FULL_CORNER_DP + SheetGeometry.CORNER_MARGIN_DP).dp)
    }

    private fun photo(id: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id",
        displayName = "p$id.jpg", mimeType = "image/jpeg", bucketName = null,
        dateTakenMillis = id, dateModifiedSeconds = id, width = 10, height = 10, sizeBytes = 1,
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    @Test
    fun `the buttons flip the stack and the counter follows`() {
        compose.setContent { PhotoStack(photos = (1L..5L).map(::photo), seed = 1L) }
        compose.onNodeWithText("1 of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription(PhotoStackCopy.NEXT).performClick()
        compose.onNodeWithText("2 of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription(PhotoStackCopy.PREVIOUS).performClick()
        compose.onNodeWithContentDescription(PhotoStackCopy.PREVIOUS).performClick()
        compose.onNodeWithText("5 of 5").assertIsDisplayed()
    }

    @Test
    fun `an empty stack is a silhouette with the buttons off`() {
        compose.setContent { PhotoStack(photos = emptyList(), seed = 1L) }
        compose.onNodeWithContentDescription(PhotoStackCopy.NEXT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(PhotoStackCopy.EMPTY).assertIsDisplayed()
    }
}
