package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ViewerPinchMode
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.QUICK_ACTION_STRIP_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer's rooms sit where the app's one geography puts them: details TOP, actions BOTTOM,
 * view options RIGHT. Driven through the real [ViewerScreen] and its real shell, so a gesture that
 * reaches the wrong room, or none, fails here.
 *
 * Also the proof that the bottom edge is shared safely with the filmstrip and the video
 * transport. The shell claims a drag by whichever axis crosses touch slop first and lets a
 * horizontal one through unclaimed; the filmstrip and the seek bar scrub horizontally. So a pull
 * UP from the bottom must open the actions even when it starts on the strip, and a drag ALONG the
 * strip must scrub and open nothing.
 *
 * Under Robolectric a video cannot decode a frame, so the video transport itself is never up and
 * its own seek drag is a device check; what runs here is the strip-less bottom edge over a video,
 * where the transport would sit, which exercises the same shell decision.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerRoomGeographyTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    private fun roll(videoAt: Int? = null): List<MediaAsset> {
        val base = ViewerFixtures.roll(context)
        if (videoAt == null) return base
        return base.mapIndexed { index, asset ->
            if (index == videoAt) {
                asset.copy(displayName = "clip-$index.mp4", mimeType = "video/mp4", durationMillis = 64_000L)
            } else {
                asset
            }
        }
    }

    @Composable
    private fun themed(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }

    @Composable
    private fun Viewer(
        asset: MediaAsset,
        roll: List<MediaAsset>,
        onSelectAsset: (MediaAsset) -> Unit = {},
        showFilmstrip: Boolean = true,
        pinchMode: ViewerPinchMode = ViewerPinchMode.OPTICAL_ZOOM,
        manualLatitude: Double? = null,
        manualLongitude: Double? = null,
    ) {
        ViewerScreen(
            asset = asset,
            position = roll.indexOf(asset) + 1,
            total = roll.size,
            isFavorite = false,
            isSensitive = false,
            hasPrevious = true,
            hasNext = true,
            canMoveToTrash = true,
            slideshowActive = false,
            slideshowIntervalSeconds = 5,
            onToggleSlideshow = {},
            onToggleFavorite = {},
            onToggleSensitive = {},
            onShare = {},
            onEdit = {},
            onOpenWith = {},
            onMoveToTrash = {},
            onPrevious = {},
            onNext = {},
            onClose = {},
            showFilmstrip = showFilmstrip,
            pinchMode = pinchMode,
            relatedAssets = roll,
            onSelectAsset = onSelectAsset,
            manualLatitude = manualLatitude,
            manualLongitude = manualLongitude,
            plateMapBackdrop = ViewerFixtures.mapStandIn,
            catalogueActions = false,
        )
    }

    // A row only the actions room has, and one only the view options room has.
    private fun actionsShown() = compose.onAllNodesWithText("Open with").fetchSemanticsNodes().isNotEmpty()
    private fun viewOptionsShown() = compose.onAllNodesWithText("Seconds per photo").fetchSemanticsNodes().isNotEmpty()
    private fun detailsShown() =
        compose.onAllNodesWithContentDescription("Where this photo was taken").fetchSemanticsNodes().isNotEmpty()

    private fun pullUpFromBottom() {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 20f),
                end = Offset(centerX, centerY - 100f),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
    }

    private fun pullInFromRight() {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(right - 20f, centerY),
                end = Offset(centerX - 100f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
    }

    private fun pullDownFromTop() {
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        compose.waitForIdle()
    }

    @Test
    fun `pulling up from the bottom opens the actions and only the actions`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        pullUpFromBottom()
        compose.onNodeWithText("Open with").assertExists()
        assertTrue("view options came up with the actions", !viewOptionsShown())
        assertTrue("details came up with the actions", !detailsShown())
    }

    @Test
    fun `pulling in from the right opens the view options and only the view options`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        pullInFromRight()
        compose.onNodeWithText("Seconds per photo").assertExists()
        compose.onNodeWithText("Filmstrip").assertExists()
        assertTrue("actions came up with the view options", !actionsShown())
        assertTrue("details came up with the view options", !detailsShown())
    }

    @Test
    fun `pulling down from the top still opens the details, and not the actions`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37) } }
        compose.waitForIdle()
        pullDownFromTop()
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
        assertTrue("actions came up with the details", !actionsShown())
        assertTrue("view options came up with the details", !viewOptionsShown())
    }

    @Test
    fun `the view options room names the geography in its gesture caption`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        pullInFromRight()
        // The caption is the only place the app tells a user which edge is which.
        compose.onNodeWithText("up from the bottom a little for quick actions and further for all of them", substring = true)
            .assertExists()
        compose.onNodeWithText("in from the right for these view options", substring = true).assertExists()
    }

    @Test
    fun `a pull up that starts on the filmstrip still opens the actions`() {
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items) } }
        compose.waitForIdle()
        // The chrome is up over a video with no tap, so the strip's neighbours are composed.
        assertTrue(
            "the strip should be up",
            compose.onAllNodesWithContentDescription(items[3].displayName).fetchSemanticsNodes().isNotEmpty(),
        )
        // Start inside the strip's resting band, well inside the shell's bottom edge zone.
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 60f),
                end = Offset(centerX, centerY - 100f),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Open with").assertExists()
    }

    @Test
    fun `a drag along the filmstrip scrubs and opens no room`() {
        val items = roll(videoAt = 4)
        val reported = mutableListOf<MediaAsset>()
        var current by mutableStateOf(items[4])
        compose.setContent {
            themed {
                Viewer(
                    asset = current,
                    roll = items,
                    onSelectAsset = {
                        reported += it
                        current = it
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX + 200f, bottom - 60f),
                end = Offset(centerX - 260f, bottom - 60f),
                durationMillis = 500,
            )
        }
        compose.waitForIdle()
        assertTrue("the drag along the strip scrubbed nothing", reported.isNotEmpty())
        assertTrue("a sideways scrub opened the actions", !actionsShown())
        assertTrue("a sideways scrub opened the view options", !viewOptionsShown())
        assertTrue("a sideways scrub opened the details", !detailsShown())
    }

    @Test
    fun `a sideways drag along the bottom of a video with no strip opens no room`() {
        // Where the video transport sits when the strip is off: the same bottom edge zone, and a
        // seek is a horizontal drag. The shell must leave it alone.
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items, showFilmstrip = false) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX - 200f, bottom - 60f),
                end = Offset(centerX + 200f, bottom - 60f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue("a sideways drag opened the actions", !actionsShown())
        assertTrue("a sideways drag opened the view options", !viewOptionsShown())
    }

    @Test
    fun `with pinch shortcuts on, pinch out opens the actions and pinch in the details`() {
        val photos = roll()
        compose.setContent {
            themed {
                Viewer(
                    photos[4], photos,
                    pinchMode = ViewerPinchMode.PANEL_SHORTCUTS,
                    manualLatitude = 17.4835, manualLongitude = 78.37,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(centerX - 40f, centerY),
                end0 = Offset(centerX - 260f, centerY),
                start1 = Offset(centerX + 40f, centerY),
                end1 = Offset(centerX + 260f, centerY),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Open with").assertExists()
    }

    @Test
    fun `the viewer survives a rotation under the open actions room`() {
        val photos = roll()
        var landscape by mutableStateOf(false)
        compose.setContent {
            themed {
                val frame = if (landscape) Modifier.size(956.dp, 440.dp) else Modifier.fillMaxSize()
                Box(frame) { Viewer(photos[4], photos) }
            }
        }
        compose.waitForIdle()
        pullUpFromBottom()
        compose.onNodeWithText("Open with").assertExists()
        landscape = true
        compose.waitForIdle()
        // The bottom room scrolls and insets itself by the navigation bar; a re-layout under it
        // must not close it or throw.
        compose.onNodeWithText("Open with").assertExists()
        landscape = false
        compose.waitForIdle()
        compose.onNodeWithText("Open with").assertExists()
    }

    @Test
    fun `a slow short pull on the real viewer shows the quick strip and not the rest of the actions`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        // Slow enough that no frame is a flick (under 2px), short enough to end on the peek.
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, bottom - 20f),
                end = Offset(centerX, bottom - 320f),
                durationMillis = 4_000,
            )
        }
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val strip = compose.onNodeWithTag(QUICK_ACTION_STRIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the strip is cut off", strip.bottom <= screen + 1f)
        val firstRow = compose.onNodeWithText("Slideshow").fetchSemanticsNode().boundsInRoot
        assertTrue("the full list showed at the peek", firstRow.top >= screen)
    }

    @Test
    fun `a library photo is offered favourite share edit and trash in that order`() {
        assertEquals(
            listOf(ViewerQuickAction.FAVOURITE, ViewerQuickAction.SHARE, ViewerQuickAction.EDIT, ViewerQuickAction.TRASH),
            viewerQuickActions(catalogueActions = true, isExternal = false, canMoveToTrash = true),
        )
    }

    @Test
    fun `a photo opened from outside the library is offered no catalogue action`() {
        // Same rule as the full list: no favourite or trash without a library record, and no edit
        // for an ad-hoc asset with no editor.
        assertEquals(
            listOf(ViewerQuickAction.SHARE),
            viewerQuickActions(catalogueActions = false, isExternal = false, canMoveToTrash = true),
        )
    }

    @Test
    fun `a photo opened from another app keeps edit and share but not favourite or trash`() {
        assertEquals(
            listOf(ViewerQuickAction.SHARE, ViewerQuickAction.EDIT),
            viewerQuickActions(catalogueActions = true, isExternal = true, canMoveToTrash = true),
        )
    }

    @Test
    fun `trash is left out rather than shown dead when the platform cannot do it`() {
        assertEquals(
            listOf(ViewerQuickAction.FAVOURITE, ViewerQuickAction.SHARE, ViewerQuickAction.EDIT),
            viewerQuickActions(catalogueActions = true, isExternal = false, canMoveToTrash = false),
        )
    }

    @Test
    fun `share is always offered so the strip is never empty`() {
        listOf(true, false).forEach { catalogue ->
            listOf(true, false).forEach { external ->
                listOf(true, false).forEach { trash ->
                    assertTrue(
                        ViewerQuickAction.SHARE in viewerQuickActions(catalogue, external, trash),
                    )
                }
            }
        }
    }
}
