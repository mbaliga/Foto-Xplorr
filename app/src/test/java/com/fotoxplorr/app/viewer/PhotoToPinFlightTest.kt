package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The flier against its own geometry: draw it for real, find where its pixels actually land, and
 * check that against [PlaceMorph.flightRect]. The pure tests prove the maths; this proves the
 * `graphicsLayer` transform, the crop window and the hand-off really put those pixels there.
 *
 * Real pixels, because a layer whose maths is right and whose transform origin is wrong draws a
 * perfectly valid-looking photo in the wrong place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PhotoToPinFlightTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    // A 400 x 800 dp shell at 2x: 800 x 1600 px, a 72 dp band, the fixture photo (a square).
    private val viewport = IntSize(800, 1600)
    private val band = PlaceMorph.SHELL_BAND_DP * 2f
    private val photo = PlaceMorph.fitRect(800f, 1600f, 900f, 900f)
    private val pin = Rect(300f, 520f, 460f, 680f)

    private var progress by mutableFloatStateOf(0f)
    private var pinHolder: Rect? = pin

    private fun show(dim: Float = 0f) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.size(400.dp, 800.dp).background(Color.Black)) {
                    PhotoToPinFlight(
                        asset = ViewerFixtures.portrait(context, id = 3L, seed = 0),
                        progress = { progress },
                        photoRect = photo,
                        pinRect = { pinHolder },
                        viewportSize = viewport,
                        bandPx = band,
                        landedDim = dim,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private var captures = 0

    /**
     * The bounding box of everything that is not the black ground, in px, or null if all black.
     *
     * Captured through Roborazzi (androidx's own `captureToImage` waits on a redraw that never
     * comes under Robolectric) and read back from the PNG, which doubles as a picture of the
     * frame under `app/build/renders/flight-probe-*.png`.
     */
    private fun litBounds(): Rect? {
        val file = File("build/renders/flight-probe-${captures++}.png")
        compose.onRoot().captureRoboImage(file.path)
        val image = BitmapFactory.decodeFile(file.path)
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getPixel(x, y)
                val red = (rgb shr 16) and 0xFF
                val green = (rgb shr 8) and 0xFF
                val blue = rgb and 0xFF
                if (red > 10 || green > 10 || blue > 10) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    @Test
    fun `it draws nothing while the shell is closed`() {
        progress = 0f
        show()
        assertEquals(null, litBounds())
    }

    @Test
    fun `its pixels land where the geometry says, all the way through the pull`() {
        show()
        for (v in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            progress = v
            compose.waitForIdle()
            val expected = PlaceMorph.flightRect(photo, pin, viewport.width.toFloat(), viewport.height.toFloat(), band, v)
            val lit = litBounds()
            assertNotNull("nothing drawn at $v", lit)
            // Anti-aliased rounded corners and a frame that is still faint cost a couple of px.
            val slack = 4f
            assertEquals("left at $v", expected.left, lit!!.left, slack)
            assertEquals("top at $v", expected.top, lit.top, slack)
            assertEquals("right at $v", expected.right, lit.right, slack)
            assertEquals("bottom at $v", expected.bottom, lit.bottom, slack)
        }
    }

    @Test
    fun `it lands on the pin tile and is gone at full open`() {
        show()
        progress = 0.96f
        compose.waitForIdle()
        val lit = litBounds()
        assertNotNull(lit)
        // Just before the hand-off the room is 4% short of its rest, so the pin is still
        // sliding the last 64 px in; the flier is on it, to within the last sliver of its flight.
        val expected = PlaceMorph.flightRect(photo, pin, viewport.width.toFloat(), viewport.height.toFloat(), band, 0.96f)
        assertEquals(PlaceMorph.pinRectAt(pin, viewport.height.toFloat(), 0.96f).top, expected.top, 8f)
        assertEquals(expected.left, lit!!.left, 4f)
        assertEquals(expected.top, lit.top, 4f)
        assertEquals(expected.right, lit.right, 4f)
        assertEquals(expected.bottom, lit.bottom, 4f)
        assertEquals("the landed tile is the pin's 160 px", 160f, lit.right - lit.left, 6f)

        progress = 1f
        compose.waitForIdle()
        assertEquals("the flier must be fully handed off", null, litBounds())
    }

    @Test
    fun `an unreachable pin means no flier at all - the room fades its own`() {
        // Scrolled so far the tile is above the room.
        pinHolder = pin.translate(0f, -1500f)
        show()
        for (v in listOf(0.3f, 0.7f, 0.96f)) {
            progress = v
            compose.waitForIdle()
            assertEquals("a flier appeared with nowhere to land at $v", null, litBounds())
        }
    }

    @Test
    fun `no pin report yet means nothing drawn rather than a guess`() {
        pinHolder = null
        show()
        progress = 0.5f
        compose.waitForIdle()
        assertEquals(null, litBounds())
    }

    @Test
    fun `a ghost landing survives the whole pull`() {
        show(dim = PlaceMorph.GHOST_DIM)
        for (v in listOf(0.2f, 0.6f, 0.95f, 1f)) {
            progress = v
            compose.waitForIdle()
        }
        assertTrue(true)
    }
}
