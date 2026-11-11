package com.fotoxplorr.app.viewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.ViewerPinchMode
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.textselect.FRAME_H
import com.fotoxplorr.app.textselect.FRAME_W
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.app.textselect.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Select text overlay and toolbar through the real Compose runtime, in the pattern of
 * [ViewerIntegrationTest]: the pure model is in the textselect tests, and this file proves the
 * layer is actually wired to it -- what is composed, what is consumed, what reaches the viewer's
 * own detectors.
 *
 * The harness is the viewer's arrangement in miniature: a parent Box running the viewer's REAL
 * one-loop gesture detector ([detectViewerGestures]) and its tap detector, with the overlay as its
 * child. At xhdpi the 512 x 256 dp box is exactly the 1024 x 512 px frame of the text fixture, so a
 * touch at (x, y) is the frame point (x, y) and the words can be aimed at by their fixture
 * coordinates (see TextFixtures.page).
 *
 * What is NOT covered here: the wiring inside [ViewerScreen] itself (Back, the chrome, the rooms),
 * because nothing opens the mode there until the entry points land; the gesture arbitration on a
 * real finger is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h700dp-xhdpi")
class TextSelectOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Counts {
        var taps = 0
        var pages = 0
        var zoom = 1f
    }

    private fun wordState(): TextSelectState = TextSelectState(TextLayout.empty(1, 1)).apply { useWordLayout(page()) }

    @Composable
    private fun Harness(
        state: TextSelectState,
        counts: Counts,
        active: Boolean = true,
        zoom: Float = 1f,
        oneFingerInert: Boolean = active,
        onDone: () -> Unit = {},
    ) {
        MaterialTheme {
            Column {
                Box(
                    Modifier
                        .size((FRAME_W / 2).dp, (FRAME_H / 2).dp)
                        .testTag(HARNESS)
                        .pointerInput(Unit) {
                            detectViewerGestures(
                                scaleProvider = { counts.zoom },
                                pinchMode = ViewerPinchMode.OPTICAL_ZOOM,
                                oneFingerInert = { oneFingerInert },
                                onTransform = { zoomChange, _, _ -> counts.zoom *= zoomChange },
                                onPage = { counts.pages++ },
                                onPinch = {},
                                onGestureEnd = {},
                            )
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { counts.taps++ }) },
                ) {
                    LiveTextOverlay(state = state, active = active, scale = { zoom })
                }
                if (active) TextSelectToolbar(state = state, onDone = onDone)
            }
        }
    }

    private fun status() = compose.onNodeWithTag(TEXT_SELECT_STATUS_TAG)

    private fun harness(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(HARNESS).performTouchInput(block)
        compose.waitForIdle()
    }

    private fun clipboardText(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    // ── nothing unless asked for ───────────────────────────────────────────────────────────────

    @Test
    fun `inactive, the layer is not in the tree and a tap reaches the viewer`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, active = false) }
        compose.waitForIdle()

        compose.onNodeWithTag(TEXT_SELECT_OVERLAY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TEXT_SELECT_STATUS_TAG).assertDoesNotExist()

        // On a word, on a block of text: the dead zone this layer used to make is gone.
        harness { click(Offset(150f, 115f)) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertEquals("the viewer's tap detector should see a tap over text", 1, counts.taps)
    }

    @Test
    fun `active, the layer is composed and claims the press so the viewer's tap stays out`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()
        compose.onNodeWithTag(TEXT_SELECT_OVERLAY_TAG).assertExists()

        harness { click(Offset(150f, 115f)) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertEquals("a tap in the mode must not also hide the chrome", 0, counts.taps)
    }

    // ── tap a block ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on text selects its block, a second tap lets go, bare photo clears`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()
        status().assertTextEquals(TextSelectCopy.HINT)

        harness { click(Offset(150f, 115f)) }
        status().assertTextEquals("4 words selected")

        harness { click(Offset(150f, 115f)) }
        status().assertTextEquals(TextSelectCopy.HINT)

        harness { click(Offset(150f, 115f)) }
        status().assertTextEquals("4 words selected")
        harness { click(Offset(950f, 40f)) }
        status().assertTextEquals(TextSelectCopy.HINT)
    }

    // ── draw ───────────────────────────────────────────────────────────────────────────────────

    private fun swipeLine(from: Offset, to: Offset) {
        compose.onNodeWithTag(HARNESS).performTouchInput { swipe(from, to, durationMillis = 200) }
        compose.waitForIdle()
    }

    @Test
    fun `a stroke selects the words it touched and the radius follows the zoom`() {
        val closeUp = Counts()
        compose.setContent { Harness(wordState(), closeUp, zoom = 4f) }
        compose.waitForIdle()
        // Ends 10 px short of "only": at 4x a 12 dp finger is 6 px and does not reach it.
        swipeLine(Offset(590f, 315f), Offset(690f, 315f))
        status().assertTextEquals("1 word selected")
    }

    @Test
    fun `the same stroke at no zoom reaches the neighbouring word`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 1f) }
        compose.waitForIdle()
        swipeLine(Offset(590f, 315f), Offset(690f, 315f))
        status().assertTextEquals("2 words selected")
    }

    @Test
    fun `a short drag at high zoom is a stroke over its word, not a tap on the block`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 4f) }
        compose.waitForIdle()
        // 10 px is under touch slop on screen units undivided (16 px) but 40 px of screen at 4x.
        swipeLine(Offset(730f, 315f), Offset(740f, 315f))
        status().assertTextEquals("1 word selected")
    }

    @Test
    fun `the same short drag with no zoom is still a tap on the block`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 1f) }
        compose.waitForIdle()
        swipeLine(Offset(730f, 315f), Offset(740f, 315f))
        status().assertTextEquals("3 words selected")
    }

    @Test
    fun `a stroke that touches no word leaves the selection as it was`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 4f) }
        compose.waitForIdle()
        harness { click(Offset(150f, 115f)) }
        status().assertTextEquals("4 words selected")

        swipeLine(Offset(700f, 40f), Offset(900f, 40f))
        status().assertTextEquals("4 words selected")
    }

    @Test
    fun `a closed loop is a lasso around the words inside it`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 4f) }
        compose.waitForIdle()
        harness {
            down(Offset(580f, 280f))
            moveTo(Offset(740f, 280f))
            moveTo(Offset(900f, 280f))
            moveTo(Offset(900f, 315f))
            moveTo(Offset(900f, 350f))
            moveTo(Offset(740f, 350f))
            moveTo(Offset(580f, 350f))
            moveTo(Offset(580f, 315f))
            moveTo(Offset(580f, 285f))
            up()
        }
        status().assertTextEquals("3 words selected")
    }

    @Test
    fun `the finger trail is gone when the stroke ends`() {
        val counts = Counts()
        val state = wordState()
        compose.setContent { Harness(state, counts, zoom = 4f) }
        compose.waitForIdle()
        harness {
            down(Offset(590f, 315f))
            moveTo(Offset(640f, 315f))
            moveTo(Offset(690f, 315f))
        }
        assertTrue("the trail should be drawn while the finger is down", state.trail.size > 1)
        harness { up() }
        assertTrue("the trail should be cleared once the finger lifts", state.trail.isEmpty())
    }

    // ── the viewer keeps pinch, loses the lone finger ──────────────────────────────────────────

    @Test
    fun `a pinch over the text layer still zooms and selects nothing`() {
        val counts = Counts()
        val state = wordState()
        compose.setContent { Harness(state, counts) }
        compose.waitForIdle()
        harness {
            pinch(
                start0 = Offset(400f, 250f), end0 = Offset(250f, 250f),
                start1 = Offset(600f, 250f), end1 = Offset(750f, 250f),
                durationMillis = 300,
            )
        }
        assertTrue("the pinch should have reached the viewer's detector (zoom ${counts.zoom})", counts.zoom > 1.2f)
        status().assertTextEquals(TextSelectCopy.HINT)
        assertTrue(state.trail.isEmpty())
    }

    @Test
    fun `a pinch begun mid-stroke abandons the stroke and keeps the selection it started from`() {
        val counts = Counts()
        val state = wordState()
        compose.setContent { Harness(state, counts, zoom = 4f) }
        compose.waitForIdle()
        harness { click(Offset(150f, 115f)) }
        status().assertTextEquals("4 words selected")

        harness {
            down(0, Offset(590f, 315f))
            moveTo(0, Offset(640f, 315f))
            moveTo(0, Offset(690f, 315f))
            down(1, Offset(900f, 450f))
            moveTo(1, Offset(950f, 480f))
            up(0)
            up(1)
        }
        status().assertTextEquals("4 words selected")
        assertTrue(state.trail.isEmpty())
    }

    @Test
    fun `with the layer on, a long one-finger swipe does not turn the page`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 4f) }
        compose.waitForIdle()
        swipeLine(Offset(950f, 40f), Offset(60f, 40f))
        assertEquals(0, counts.pages)
        assertEquals(1f, counts.zoom, 0.0001f)
    }

    @Test
    fun `with the layer off, the same swipe turns the page`() {
        // The control: this harness really does page, so the test above proves the guard.
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, active = false, oneFingerInert = false) }
        compose.waitForIdle()
        swipeLine(Offset(950f, 40f), Offset(60f, 40f))
        assertEquals(1, counts.pages)
    }

    // ── the toolbar ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Copy puts only the selected words on the clipboard`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts, zoom = 4f) }
        compose.waitForIdle()
        // Along the first line, y = 115: "PLATFORM" and "9" and nothing from the line below.
        swipeLine(Offset(90f, 115f), Offset(360f, 115f))
        status().assertTextEquals("2 words selected")

        compose.onNodeWithText("Copy").performClick()
        compose.waitForIdle()
        assertEquals("PLATFORM 9", clipboardText())
        status().assertTextEquals("Copied 2 words")
    }

    @Test
    fun `changing the selection ends the Copied announcement`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()
        harness { click(Offset(150f, 115f)) }
        compose.onNodeWithText("Copy").performClick()
        status().assertTextEquals("Copied 4 words")
        harness { click(Offset(700f, 315f)) }
        status().assertTextEquals("3 words selected")
    }

    @Test
    fun `Select all takes every word, in reading order, and Deselect lets go`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()

        compose.onNodeWithText("Select all").performClick()
        status().assertTextEquals("7 words selected")
        compose.onNodeWithText("Copy").performClick()
        assertEquals("PLATFORM 9\nNorthern line\nExit only left", clipboardText())

        // The same button now offers the way back.
        compose.onNodeWithText("Select all").assertDoesNotExist()
        compose.onNodeWithText("Deselect").performClick()
        status().assertTextEquals(TextSelectCopy.HINT)
    }

    @Test
    fun `the buttons are labelled, clickable, at least 48 dp, and Copy waits for a selection`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()

        for (label in listOf("Select all", "Copy", "Done")) {
            compose.onNodeWithText(label)
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        compose.onNodeWithText("Select all").assertIsEnabled()
        compose.onNodeWithText("Done").assertIsEnabled()

        harness { click(Offset(150f, 115f)) }
        compose.onNodeWithText("Copy").assertIsEnabled()
    }

    @Test
    fun `the status line is a polite live region`() {
        val counts = Counts()
        compose.setContent { Harness(wordState(), counts) }
        compose.waitForIdle()
        status().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `Done leaves the mode`() {
        val counts = Counts()
        var done = 0
        compose.setContent { Harness(wordState(), counts, onDone = { done++ }) }
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `a photo with no readable text says so and leaves only Done`() {
        val counts = Counts()
        compose.setContent { Harness(TextSelectState(TextLayout.empty(FRAME_W, FRAME_H)), counts) }
        compose.waitForIdle()
        status().assertTextEquals("No selectable text found")
        compose.onNodeWithText("Select all").assertIsNotEnabled()
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        compose.onNodeWithText("Done").assertIsEnabled()
    }

    // ── the block-only layout, then the word-level one ─────────────────────────────────────────

    @Test
    fun `a block tapped before the word pass returns survives it as its words`() {
        val counts = Counts()
        val stored = listOf(
            TextBlock("PLATFORM 9\nNorthern line", 100f / FRAME_W, 100f / FRAME_H, 350f / FRAME_W, 180f / FRAME_H),
            TextBlock("Exit only left", 600f / FRAME_W, 300f / FRAME_H, 880f / FRAME_W, 330f / FRAME_H),
        )
        val state = TextSelectState(TextLayout.fromLegacyBlocks(stored, FRAME_W, FRAME_H))
        compose.setContent { Harness(state, counts) }
        compose.waitForIdle()

        // Tap-a-block works at once, from the stored boxes.
        harness { click(Offset(700f, 315f)) }
        status().assertTextEquals("1 block selected")
        compose.onNodeWithText("Copy").performClick()
        assertEquals("Exit only left", clipboardText())

        compose.runOnUiThread { state.useWordLayout(page()) }
        compose.waitForIdle()
        status().assertTextEquals("3 words selected")
    }

    private companion object {
        const val HARNESS = "textSelectHarness"
    }
}
