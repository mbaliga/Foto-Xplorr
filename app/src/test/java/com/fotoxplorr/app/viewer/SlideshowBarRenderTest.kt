package com.fotoxplorr.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.hyle.SelectionToolbarShape
import com.fotoxplorr.app.hyle.SelectionToolbarTallShape
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The slideshow bar drawn on its own (owner, #16), because the cove is the part that has to be
 * looked at: it must still meet the top edge at its designed radius when the bar is taller to
 * clear a status bar, and with no status bar it must be the gallery's toolbar exactly.
 *
 * Each case renders the bar on a mid-grey ground (so black ink is distinguishable from the photo
 * it hangs over) into `build/renders/slideshow-bar-*.png`, and reads pixels back from that file.
 * The geometry is the mockup's, in dp, at 2 px per dp (xhdpi).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SlideshowBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Pixels per dp. Named so it is not shadowed by `Bitmap.density` inside the Bitmap helpers. */
    private val pxPerDp: Float
        get() = context.resources.displayMetrics.density

    private val ground = Color(0xFF8C8C8C)

    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            // The node under test is captured on its own, so the stage only needs a ground; room
            // around it lets the shadow fall where the capture will not clip it.
            Box(Modifier.fillMaxSize().background(ground)) { content() }
        }
    }

    private fun renderBar(name: String, topInset: Dp, playing: Boolean = true, hasPrevious: Boolean = true): Bitmap {
        compose.setContent {
            Stage {
                SlideshowBar(
                    playing = playing,
                    hasPrevious = hasPrevious,
                    onPrevious = {},
                    onPlayPause = {},
                    onNext = {},
                    topInset = topInset,
                )
            }
        }
        compose.waitForIdle()
        val path = "build/renders/$name.png"
        compose.onNodeWithTag(SLIDESHOW_BAR_TAG).captureRoboImage(path)
        return checkNotNull(BitmapFactory.decodeFile(path)) { "could not read $path" }
    }

    /** Luminance 0..255 of the pixel at ([xDp], [yDp]) in the bar's own coordinates. */
    private fun Bitmap.lum(xDp: Float, yDp: Float): Int {
        val x = (xDp * pxPerDp).toInt().coerceIn(0, width - 1)
        val y = (yDp * pxPerDp).toInt().coerceIn(0, height - 1)
        val argb = getPixel(x, y)
        return (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3
    }

    private fun Bitmap.isBlack(xDp: Float, yDp: Float) = lum(xDp, yDp) < 0x20

    private fun Bitmap.isGround(xDp: Float, yDp: Float) = lum(xDp, yDp) > 0x50

    /** Whether any near-white pixel (a glyph) lies in the box, in dp. */
    private fun Bitmap.hasInk(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val x1 = (right * pxPerDp).toInt().coerceAtMost(width)
        val y1 = (bottom * pxPerDp).toInt().coerceAtMost(height)
        for (y in (top * pxPerDp).toInt() until y1) {
            for (x in (left * pxPerDp).toInt() until x1) {
                val argb = getPixel(x, y)
                val lum = (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3
                if (lum > 0xD0) return true
            }
        }
        return false
    }

    /** A pixel count within one of what the design height or width gives. */
    private fun assertPx(message: String, expectedDp: Float, actualPx: Int) =
        assertEquals(message, expectedDp * pxPerDp, actualPx.toFloat(), 1.5f)

    // ── no status bar: the gallery's toolbar ───────────────────────────────────────────────────

    @Test
    fun `with no inset the bar is the gallery toolbar's shape`() {
        val bar = renderBar("slideshow-bar-inset-0", 0.dp)
        assertPx("the design width at 2px per dp", 209.076f, bar.width)
        assertPx("the design height", 50.7891f, bar.height)
        // Flush to the top edge: black along it, between the two coves.
        assertTrue("top edge, middle", bar.isBlack(104f, 0.5f))
        // The cove: outside the curve at the top left, inside it lower down.
        assertTrue("outside the top-left cove", bar.isGround(2f, 1f))
        assertTrue("outside the left side at mid height", bar.isGround(20f, 30f))
        assertTrue("inside the body", bar.isBlack(80f, 46f))
        // The mirror image on the right.
        assertTrue("outside the top-right cove", bar.isGround(207f, 1f))
        assertTrue("outside the right side", bar.isGround(190f, 30f))
        assertTrue("the flat bottom", bar.isBlack(105f, 49f))
    }

    @Test
    fun `a zero inset draws the same pixels as the plain toolbar shape`() {
        compose.setContent {
            Stage {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    Box(
                        Modifier
                            .background(Color.Black, SelectionToolbarShape)
                            .size(209.076f.dp, 50.7891f.dp)
                            .testTag("plain"),
                    )
                    Box(
                        Modifier
                            .padding(top = 16.dp)
                            .background(Color.Black, SelectionToolbarTallShape)
                            .size(209.076f.dp, 50.7891f.dp)
                            .testTag("tall"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("plain").captureRoboImage("build/renders/slideshow-shape-plain.png")
        compose.onNodeWithTag("tall").captureRoboImage("build/renders/slideshow-shape-tall-0.png")
        val plain = checkNotNull(BitmapFactory.decodeFile("build/renders/slideshow-shape-plain.png"))
        val tall = checkNotNull(BitmapFactory.decodeFile("build/renders/slideshow-shape-tall-0.png"))
        assertEquals(plain.width, tall.width)
        assertEquals(plain.height, tall.height)
        // The two boxes lay out to whole pixels, so their scales differ by a hair and an edge pixel
        // can land a shade apart; what must not differ is the outline, which a different shape
        // would move by whole pixels. So count only pixels that flipped between ink and ground.
        var flipped = 0
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            val a = plain.getPixel(x, y) and 0xFF
            val b = tall.getPixel(x, y) and 0xFF
            if (kotlin.math.abs(a - b) > 0x80) flipped++
        }
        assertEquals("pixels that flipped between the plain and the stretched-by-nothing shape", 0, flipped)
    }

    // ── under a status bar ─────────────────────────────────────────────────────────────────────

    @Test
    fun `under a 24dp status bar the bar stretches and its coves still meet the top edge`() {
        val inset = 24f
        val bar = renderBar("slideshow-bar-inset-24", inset.dp)
        assertPx("the inset is added to the design height", 50.7891f + inset, bar.height)
        // The cove is where it was: same radius, at the top edge, not lowered by the inset.
        assertTrue("black along the top edge", bar.isBlack(104f, 0.5f))
        assertTrue("outside the top-left cove", bar.isGround(2f, 1f))
        assertTrue("outside the top-right cove", bar.isGround(207f, 1f))
        // The side runs from the cove down: at the status bar's own height it is still part-way.
        assertTrue("black inside the body under the status bar", bar.isBlack(60f, inset - 2f))
        assertTrue("the side has gone in by the cove's foot", bar.isGround(15f, 14f))
        // And the body carries on to the stretched bottom.
        assertTrue("the flat bottom", bar.isBlack(105f, inset + 49f))
        assertTrue("outside the stretched left side", bar.isGround(20f, inset + 30f))
    }

    @Test
    fun `the glyphs sit below the status bar and the status bar's strip holds none`() {
        val inset = 24f
        val bar = renderBar("slideshow-bar-inset-24-glyphs", inset.dp)
        assertFalse("no ink in the status bar's own strip", bar.hasInk(0f, 0f, 209f, inset - 1f))
        assertTrue("previous glyph below it", bar.hasInk(40f, inset, 80f, inset + 50f))
        assertTrue("play or pause glyph", bar.hasInk(88f, inset, 122f, inset + 50f))
        assertTrue("next glyph", bar.hasInk(135f, inset, 175f, inset + 50f))
    }

    @Test
    fun `a deep 48dp inset keeps the cove the same size`() {
        val inset = 48f
        val bar = renderBar("slideshow-bar-inset-48", inset.dp)
        assertPx("height", 50.7891f + inset, bar.height)
        assertTrue("cove at the top edge", bar.isGround(2f, 1f))
        assertTrue("black body", bar.isBlack(105f, inset + 25f))
    }

    // ── the controls ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `previous is dimmed and inert when there is no previous photo`() {
        renderBar("slideshow-bar-first-photo", 0.dp, hasPrevious = false)
        compose.onNodeWithTag(SLIDESHOW_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SLIDESHOW_NEXT_TAG).assertIsEnabled()
        compose.onNodeWithTag(SLIDESHOW_PLAY_PAUSE_TAG).assertIsEnabled()
    }

    @Test
    fun `the middle glyph says what a press does`() {
        var presses = 0
        compose.setContent {
            Stage {
                var playing by remember { mutableStateOf(true) }
                SlideshowBar(
                    playing = playing,
                    hasPrevious = true,
                    onPrevious = {},
                    onPlayPause = { presses++; playing = !playing },
                    onNext = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause slideshow").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Resume slideshow").assertExists()
        compose.onNodeWithContentDescription("Resume slideshow").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause slideshow").assertExists()
        assertEquals(2, presses)
    }

    @Test
    fun `the targets are at least 48dp square`() {
        renderBar("slideshow-bar-targets", 0.dp)
        for (tag in listOf(SLIDESHOW_PREVIOUS_TAG, SLIDESHOW_PLAY_PAUSE_TAG, SLIDESHOW_NEXT_TAG)) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is ${bounds.width / pxPerDp}dp wide", bounds.width / pxPerDp >= 47.9f)
            assertTrue("$tag is ${bounds.height / pxPerDp}dp tall", bounds.height / pxPerDp >= 47.9f)
        }
    }
}
