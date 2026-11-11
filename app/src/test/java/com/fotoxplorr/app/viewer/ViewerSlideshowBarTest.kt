package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The slideshow bar in the real [ViewerScreen] (owner, #16): that it appears with a slideshow, that
 * its presses reach the host, that a pause really stops the countdown, that the chrome folds away
 * after a few seconds and a tap brings it back, and that a video holds it up. The rules are pinned
 * without Compose in [ViewerSlideshowPolicyTest]; this proves the screen applies them.
 *
 * Playback is not asserted (a video cannot decode a frame under Robolectric): a video here is a
 * clip that is not playing, which is the state the screen holds the chrome up for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerSlideshowBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    private class Calls {
        var previous = 0
        var next = 0
        var advance = 0
        var toggles = 0
        val paused = mutableListOf<Boolean>()
    }

    @Composable
    private fun Viewer(
        asset: MediaAsset,
        roll: List<MediaAsset>,
        calls: Calls,
        slideshowActive: Boolean = true,
        slideshowPaused: Boolean = false,
        hasPrevious: Boolean = true,
        intervalSeconds: Int = 2,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ViewerScreen(
                    asset = asset,
                    position = roll.indexOf(asset) + 1,
                    total = roll.size,
                    isFavorite = false,
                    isSensitive = false,
                    hasPrevious = hasPrevious,
                    hasNext = true,
                    canMoveToTrash = false,
                    slideshowActive = slideshowActive,
                    slideshowIntervalSeconds = intervalSeconds,
                    onToggleSlideshow = { calls.toggles++ },
                    onToggleFavorite = {},
                    onToggleSensitive = {},
                    onShare = {},
                    onEdit = {},
                    onOpenWith = {},
                    onMoveToTrash = {},
                    onPrevious = { calls.previous++ },
                    onNext = { calls.next++ },
                    onClose = {},
                    slideshowPaused = slideshowPaused,
                    onSetSlideshowPaused = { calls.paused += it },
                    onSlideshowAdvance = { calls.advance++ },
                    relatedAssets = roll,
                    plateMapBackdrop = ViewerFixtures.mapStandIn,
                    catalogueActions = false,
                )
            }
        }
    }

    private fun roll(): List<MediaAsset> = ViewerFixtures.roll(context)

    private fun videoAt(index: Int): List<MediaAsset> = roll().mapIndexed { i, asset ->
        if (i == index) asset.copy(displayName = "clip-$i.mp4", mimeType = "video/mp4", durationMillis = 64_000L) else asset
    }

    private fun barCount() = compose.onAllNodesWithTag(SLIDESHOW_BAR_TAG).fetchSemanticsNodes().size

    private fun closeCount() = compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().size

    private fun tap() {
        compose.onRoot().performTouchInput { click(center) }
        // A single tap is only decided once the double-tap window has passed.
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    // ── when it is drawn ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a slideshow brings the chrome up with the bar on a photo that opened bare`() {
        val items = roll()
        compose.setContent { Viewer(items[4], items, Calls()) }
        compose.waitForIdle()
        assertEquals("the bar", 1, barCount())
        assertEquals("and the chip row's Close with it", 1, closeCount())
        compose.onRoot().captureRoboImage("build/renders/viewer-slideshow-bar.png")
    }

    @Test
    fun `no bar without a slideshow`() {
        val items = roll()
        compose.setContent { Viewer(items[4], items, Calls(), slideshowActive = false) }
        compose.waitForIdle()
        tap()
        assertEquals("the chrome is up from the tap", 1, closeCount())
        assertEquals("but there is no bar", 0, barCount())
    }

    @Test
    fun `no bar for a single photo`() {
        val items = roll().take(1)
        compose.setContent { Viewer(items[0], items, Calls()) }
        compose.waitForIdle()
        assertEquals(0, barCount())
    }

    @Test
    fun `the bar sits above the chip row and the two do not overlap`() {
        val items = roll()
        compose.setContent { Viewer(items[4], items, Calls()) }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag(SLIDESHOW_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        assertTrue("the bar hangs from the top edge (top = ${bar.top})", bar.top <= 1f)
        assertTrue("the Close button starts below the bar (${close.top} vs ${bar.bottom})", close.top >= bar.bottom)
    }

    @Test
    fun `previous is inert on the first photo`() {
        val items = roll()
        compose.setContent { Viewer(items[0], items, Calls(), hasPrevious = false) }
        compose.waitForIdle()
        compose.onNodeWithTag(SLIDESHOW_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SLIDESHOW_NEXT_TAG).assertIsEnabled()
    }

    // ── what its glyphs do ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the glyphs reach the host`() {
        val items = roll()
        val calls = Calls()
        compose.setContent { Viewer(items[4], items, calls) }
        compose.waitForIdle()

        compose.onNodeWithTag(SLIDESHOW_PREVIOUS_TAG).performClick()
        compose.onNodeWithTag(SLIDESHOW_NEXT_TAG).performClick()
        compose.onNodeWithTag(SLIDESHOW_PLAY_PAUSE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, calls.previous)
        assertEquals("Next is the plain next, not the countdown's advance", 1, calls.next)
        assertEquals(0, calls.advance)
        assertEquals("playing, so the press asks to pause", listOf(true), calls.paused)
        assertEquals("the bar never stops the slideshow", 0, calls.toggles)
    }

    @Test
    fun `a paused bar offers to resume`() {
        val items = roll()
        val calls = Calls()
        compose.setContent { Viewer(items[4], items, calls, slideshowPaused = true) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Resume slideshow").performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), calls.paused)
    }

    // ── a pause really stops the countdown ─────────────────────────────────────────────────────

    @Test
    fun `the countdown calls the slideshow advance and not the plain next`() {
        val items = roll()
        val calls = Calls()
        compose.setContent { Viewer(items[4], items, calls, intervalSeconds = 2) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500L)
        compose.waitForIdle()
        assertEquals(1, calls.advance)
        assertEquals(0, calls.next)
    }

    @Test
    fun `a paused slideshow does not count down and resuming restarts a full interval`() {
        val items = roll()
        val calls = Calls()
        var paused by mutableStateOf(true)
        compose.setContent { Viewer(items[4], items, calls, slideshowPaused = paused, intervalSeconds = 2) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(10_000L)
        compose.waitForIdle()
        assertEquals("ten seconds paused", 0, calls.advance)

        paused = false
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("not yet: the interval starts afresh", 0, calls.advance)
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        assertEquals(1, calls.advance)
    }

    // ── the chrome folds away and a tap brings it back ─────────────────────────────────────────

    @Test
    fun `the chrome and the bar fold away after three seconds and a tap brings both back`() {
        val items = roll()
        // A long interval so the countdown does not interfere with the chrome's own timer.
        compose.setContent { Viewer(items[4], items, Calls(), intervalSeconds = 12) }
        compose.waitForIdle()
        assertEquals(1, barCount())

        compose.mainClock.advanceTimeBy(2_500L)
        compose.waitForIdle()
        assertEquals("still up at 2.5 s", 1, barCount())

        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("the bar is gone", 0, barCount())
        assertEquals("and so is the chip row", 0, closeCount())

        tap()
        assertEquals("a tap brings the bar back", 1, barCount())
        assertEquals("and the chip row", 1, closeCount())

        compose.mainClock.advanceTimeBy(3_500L)
        compose.waitForIdle()
        assertEquals("and it folds away again", 0, barCount())
    }

    @Test
    fun `a press on the bar restarts the three seconds`() {
        val items = roll()
        compose.setContent { Viewer(items[4], items, Calls(), intervalSeconds = 12) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500L)
        compose.onNodeWithTag(SLIDESHOW_NEXT_TAG).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500L)
        compose.waitForIdle()
        assertEquals("5 s since the start but 2.5 s since the press", 1, barCount())
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals(0, barCount())
    }

    @Test
    fun `a paused slideshow keeps the bar up`() {
        val items = roll()
        compose.setContent { Viewer(items[4], items, Calls(), slideshowPaused = true) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(20_000L)
        compose.waitForIdle()
        assertEquals("the bar is how it resumes", 1, barCount())
    }

    @Test
    fun `a video holds the bar and the chip row up`() {
        val items = videoAt(4)
        compose.setContent { Viewer(items[4], items, Calls(), intervalSeconds = 12) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(10_000L)
        compose.waitForIdle()
        assertEquals("the bar stays for the clip", 1, barCount())
        assertEquals(1, closeCount())
    }

    @Test
    fun `a slideshow that is stopped takes the bar with it`() {
        val items = roll()
        var active by mutableStateOf(true)
        compose.setContent { Viewer(items[4], items, Calls(), slideshowActive = active) }
        compose.waitForIdle()
        assertEquals(1, barCount())
        active = false
        compose.waitForIdle()
        assertEquals(0, barCount())
    }
}
