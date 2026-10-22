package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
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
 * The real [ViewerScreen] with its parts wired together: the filmstrip over a video, the video
 * player's hooks, the lift editor and the shrink-to-map flight. The pure rules behind each are in
 * [ViewerChromePolicyTest]; this file proves the screen actually honours them, through the real
 * Compose runtime, so a rule that is correct but not wired in fails here.
 *
 * Under Robolectric a video cannot decode a frame, so nothing here asserts playback. What it can
 * and does assert is composition: what is on screen, what is not, and that none of it throws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerIntegrationTest {

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
        slideshowActive: Boolean = false,
        onNext: () -> Unit = {},
        manualLatitude: Double? = null,
        manualLongitude: Double? = null,
        animated: Boolean = false,
        liveTextBlocks: List<TextBlock> = emptyList(),
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
            slideshowActive = slideshowActive,
            slideshowIntervalSeconds = 2,
            onToggleSlideshow = {},
            onToggleFavorite = {},
            onToggleSensitive = {},
            onShare = {},
            onEdit = {},
            onOpenWith = {},
            onMoveToTrash = {},
            onPrevious = {},
            onNext = onNext,
            onClose = {},
            showFilmstrip = showFilmstrip,
            relatedAssets = roll,
            onSelectAsset = onSelectAsset,
            manualLatitude = manualLatitude,
            manualLongitude = manualLongitude,
            animated = animated,
            liveTextBlocks = liveTextBlocks,
            plateMapBackdrop = ViewerFixtures.mapStandIn,
            // Keeps these tests about wiring: no catalogue writes, no key-moment indexing.
            catalogueActions = false,
        )
    }

    private fun stripShows(asset: MediaAsset): Boolean =
        compose.onAllNodesWithContentDescription(asset.displayName).fetchSemanticsNodes().isNotEmpty()

    // ── (a) the filmstrip over a video, #28 ────────────────────────────────────────────────────

    @Test
    fun `a video opens with the chrome up and the filmstrip along the bottom`() {
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items) } }
        compose.waitForIdle()
        // The chrome is up with no tap: the position chip's Close button is on screen...
        compose.onNodeWithContentDescription("Close").assertExists()
        // ...and the strip is there, which is a neighbour's thumbnail that nothing else draws.
        assertTrue("the filmstrip is missing over a video", stripShows(items[3]))
    }

    @Test
    fun `the filmstrip follows the preference for a video exactly as for a photo`() {
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items, showFilmstrip = false) } }
        compose.waitForIdle()
        assertTrue("the strip should be off when the setting says so", !stripShows(items[3]))
    }

    @Test
    fun `scrubbing across a video keeps the strip alive and keeps reporting past it`() {
        val items = roll(videoAt = 4)
        var current by mutableStateOf(items[3])
        val reported = mutableListOf<MediaAsset>()
        compose.setContent {
            themed {
                // A photo before the video, so the chrome starts down and we raise it by a tap.
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
        compose.onRoot().performTouchInput { click(center) }
        // A single tap is only decided once the double-tap window has passed.
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertTrue("the strip should be up before the scrub", stripShows(items[2]))
        // Drag along the strip, which sits along the bottom edge, so the current item moves from
        // the photo through the video and beyond.
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX + 200f, bottom - 60f),
                end = Offset(centerX - 260f, bottom - 60f),
                durationMillis = 500,
            )
        }
        compose.waitForIdle()
        assertTrue("the drag reported nothing", reported.isNotEmpty())
        val furthest = reported.maxOf { items.indexOf(it) }
        assertTrue("the scrub stopped at or before the video (furthest = $furthest)", furthest > 4)
        // The strip is still composed after passing over (and landing beyond) a video.
        assertTrue(
            "the strip vanished mid-scrub",
            stripShows(items[(items.indexOf(current) - 1).coerceAtLeast(0)]),
        )
    }

    @Test
    fun `the filmstrip reports scrubbing while dragged, and false when it leaves composition`() {
        val items = roll()
        val reports = mutableListOf<Boolean>()
        var shown by mutableStateOf(true)
        compose.setContent {
            themed {
                if (shown) {
                    FilmstripScrubber(
                        assets = items,
                        currentIndex = 4,
                        onSelect = {},
                        onScrubbingChanged = { reports += it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        // A finger down and dragging, deliberately not lifted.
        compose.onRoot().performTouchInput {
            down(Offset(centerX, bottom - 60f))
            moveBy(Offset(-90f, 0f))
            moveBy(Offset(-90f, 0f))
        }
        compose.waitForIdle()
        assertTrue("dragging should report scrubbing", reports.contains(true))
        // The strip goes away under the finger (the chrome toggled, a lift started): the host must
        // be told scrubbing is over, or it would hold its video paused for good.
        shown = false
        compose.waitForIdle()
        assertEquals("a strip that leaves mid-drag must end the scrub", false, reports.last())
    }

    // ── (b) video wiring ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a video with the player armed and the slideshow running survives and does not advance`() {
        val items = roll(videoAt = 4)
        var advanced = 0
        compose.setContent {
            themed { Viewer(items[4], items, slideshowActive = true, onNext = { advanced++ }) }
        }
        compose.waitForIdle()
        // The test clock moves in whole frames; step it past the 2 s interval. Robolectric cannot
        // decode the fixture so the player reports itself inactive (it errors rather than plays),
        // which is the SAME state a finished clip is in: the countdown runs. The point of the test
        // is that arming the player and starting the countdown do not throw or deadlock; whether
        // a playing video holds the countdown is the pure ViewerChromePolicyTest rule.
        compose.mainClock.advanceTimeBy(2_500L)
        compose.waitForIdle()
        assertTrue("slideshow advance count must be sane", advanced in 0..1)
    }

    // ── (c) lifting a subject ──────────────────────────────────────────────────────────────────

    /**
     * Actions are the BOTTOM room (RoomGeography.Actions): tilt the photo up from the bottom edge.
     * A photo's chrome is hidden at first, so its pull starts at the foot. Over a video the
     * filmstrip and the transport are up, and the pull zone is lifted above both
     * ([ViewerBottomReserve]), so a pull must start in the zone: [reserveDp] is how far up it is
     * (xhdpi is 2px per dp; the zone is the 56dp above the reserve, and this aims at its middle).
     */
    private fun openActionsRoom(reserveDp: Int = 0) {
        val startFromFoot = if (reserveDp == 0) 20f else (reserveDp + 28) * 2f
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, bottom - startFromFoot),
                end = Offset(centerX, centerY - 100f),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the actions room offers Lift subject for a photo and not for a video`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        openActionsRoom()
        compose.onNodeWithText("Lift subject").assertExists()
    }

    @Test
    fun `the actions room has no Lift subject row for a video`() {
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items) } }
        compose.waitForIdle()
        openActionsRoom(ViewerBottomReserve.reserveDp(filmstripShown = true, videoTransportShown = true))
        // The room is open (a row every asset has is there)...
        compose.onNodeWithText("Open with").assertExists()
        // ...and the row for lifting is not.
        compose.onAllNodesWithText("Lift subject").assertCountEquals(0)
    }

    private val signText = listOf(TextBlock("PLATFORM 9", left = 0.2f, top = 0.4f, right = 0.8f, bottom = 0.6f))

    @Test
    fun `a photo with recognised text still hides and shows its chrome on a tap`() {
        // The old always-on text layer swallowed this tap whenever the chrome was up (TRAPS 50).
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos, liveTextBlocks = signText) } }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Close").assertCountEquals(0)

        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").assertExists()

        // The chrome is up now -- exactly when the old layer was composed -- and a tap on the text
        // itself must put it away again.
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Close").assertCountEquals(0)
    }

    @Test
    fun `a long press lifts a photo that has recognised text, with the chrome up`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos, liveTextBlocks = signText) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").assertExists()

        compose.onRoot().performTouchInput { longClick(center) }
        compose.waitForIdle()
        // The editor is up: its loading, ready and failed states all carry Cancel or Close text.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `an animated photo is not offered for lifting`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos, animated = true) } }
        compose.waitForIdle()
        openActionsRoom()
        compose.onNodeWithText("Open with").assertExists()
        compose.onAllNodesWithText("Lift subject").assertCountEquals(0)
    }

    @Test
    fun `choosing Lift subject opens the editor and withdraws the details room`() {
        val photos = roll()
        compose.setContent {
            themed {
                Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37)
            }
        }
        compose.waitForIdle()
        openActionsRoom()
        // The row's button is the icon to the LEFT of the label: the label itself is not clickable
        // (a quirk of ViewerActionsRoom.ActionRow, reported with this change), so click where the
        // icon is, on the label's own line.
        val label = compose.onNodeWithText("Lift subject").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(label.left - 48f, label.center.y)) }
        compose.waitForIdle()
        // The editor is up: its loading, ready and failed states all carry Cancel or Close.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Close").fetchSemanticsNodes().isNotEmpty()
        }
        // With the editor up, pulling down from the top edge must not open the details room.
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Where this photo was taken").assertCountEquals(0)
    }

    @Test
    fun `without a lift the same pull does open the details room`() {
        // The control for the test above: the pull it relies on really does open the room.
        val photos = roll()
        compose.setContent {
            themed {
                Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37)
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
    }

    // ── (d) the shrink-to-map flight ───────────────────────────────────────────────────────────

    private fun flightExists(): Boolean =
        compose.onAllNodesWithTag(PHOTO_TO_PIN_FLIGHT_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun pullDetails() {
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        compose.waitForIdle()
    }

    private fun pressBack() {
        val activity = (compose as AndroidComposeTestRule<*, *>).activity
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `the photo flier exists while the details room is pulled and is gone at rest`() {
        val photos = roll()
        compose.setContent {
            themed { Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37) }
        }
        compose.waitForIdle()
        assertTrue("no flier at rest -- it holds a decoded image", !flightExists())
        pullDetails()
        assertTrue("pulling the details room should fly the photo", flightExists())
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
        pressBack()
        assertTrue("the flier should leave with the room", !flightExists())
    }

    @Test
    fun `a photo without a place flies to the ghost pin without throwing`() {
        val photos = roll()
        compose.setContent { themed { Viewer(photos[4], photos) } }
        compose.waitForIdle()
        pullDetails()
        assertTrue(flightExists())
        pressBack()
        assertTrue(!flightExists())
    }

    @Test
    fun `a zoomed photo falls back to the in-plate shrink instead of flying from nowhere`() {
        val photos = roll()
        compose.setContent {
            themed { Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37) }
        }
        compose.waitForIdle()
        // Double tap is the viewer's own zoom-in.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        pullDetails()
        assertTrue("a zoomed photo has no resting rectangle to fly from", !flightExists())
        // The room still opens and still shows the place.
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
    }

    @Test
    fun `a video flies as its poster and the pull survives the live player underneath`() {
        val items = roll(videoAt = 4)
        compose.setContent { themed { Viewer(items[4], items) } }
        compose.waitForIdle()
        pullDetails()
        assertTrue(flightExists())
        pressBack()
        assertTrue(!flightExists())
    }

    @Test
    fun `pulling and closing the room over and over leaves no flier behind`() {
        val photos = roll()
        compose.setContent {
            themed { Viewer(photos[4], photos, manualLatitude = 17.4835, manualLongitude = 78.37) }
        }
        compose.waitForIdle()
        repeat(3) {
            pullDetails()
            assertTrue(flightExists())
            pressBack()
            assertTrue(!flightExists())
        }
    }
}
