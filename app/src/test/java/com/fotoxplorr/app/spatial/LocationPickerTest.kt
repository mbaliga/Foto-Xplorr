package com.fotoxplorr.app.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The location picker's three jobs -- place on tap, fine-tune by dragging the pin, keep
 * [LocationPicker]'s callbacks to once per gesture -- and the things the old field got wrong:
 * a pin at 0 N 0 E before anyone touched it, a 1.6:1 box in a 1:1 room, a grid that never stopped
 * turning.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class LocationPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val sets = mutableListOf<Pair<Double, Double>>()
    private var cleared = 0
    private var written = 0
    private var done = 0

    private fun picker(latitude: Double? = null, longitude: Double? = null, writable: Boolean = true) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    LocationPicker(
                        latitude = latitude,
                        longitude = longitude,
                        onSet = { lat, lon -> sets += lat to lon },
                        onClear = { cleared++ },
                        onWriteIntoFile = if (writable) ({ written++ }) else null,
                        onDone = { done++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A single tap is reported after the double-tap window, so the clock has to pass it. */
    private fun settleTap() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun `no pin is drawn and nothing is reported before a place exists`() {
        picker()
        compose.onNodeWithContentDescription("Chosen place", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Remove this location").assertDoesNotExist()
        compose.onNodeWithText("Also write into the photo file").assertDoesNotExist()
        assertTrue("nothing may be reported before a tap", sets.isEmpty())
        // Static: reaching idle at all means no infinite animation is running.
        compose.waitForIdle()
    }

    @Test
    fun `the field is the plate's square, not a wide rectangle`() {
        picker()
        val size = compose.onNodeWithContentDescription("Pick a place on the map").fetchSemanticsNode().size
        assertEquals(size.width, size.height)
    }

    @Test
    fun `one tap places the pin and reports exactly once`() {
        picker()
        val field = compose.onNodeWithContentDescription("Pick a place on the map")
        val size = field.fetchSemanticsNode().size
        field.performTouchInput { click(Offset(size.width * 0.75f, size.height * 0.25f)) }
        settleTap()
        compose.onNodeWithContentDescription("Chosen place", useUnmergedTree = true).assertExists()
        assertEquals(1, sets.size)
        // The unplaced map shows the whole world in Web Mercator: three quarters across is 90 E, a
        // quarter down is where a quarter of the world's height falls (not linear in latitude).
        assertEquals(com.fotoxplorr.app.basemap.MapMath.yToLat(0.25), sets[0].first, 0.5)
        assertEquals(90.0, sets[0].second, 0.5)
    }

    @Test
    fun `dragging the pin reports once at the end, not on every frame`() {
        picker()
        val field = compose.onNodeWithContentDescription("Pick a place on the map")
        val size = field.fetchSemanticsNode().size
        val start = Offset(size.width * 0.5f, size.height * 0.5f)
        field.performTouchInput { click(start) }
        settleTap()
        assertEquals(1, sets.size)

        val end = Offset(size.width * 0.8f, size.height * 0.3f)
        field.performTouchInput { swipe(start, end, durationMillis = 300) }
        compose.waitForIdle()
        // The tap, plus ONE report for the whole drag.
        assertEquals(2, sets.size)
        assertEquals(com.fotoxplorr.app.basemap.MapMath.xToLon(0.8), sets[1].second, 1.0)
        assertEquals(com.fotoxplorr.app.basemap.MapMath.yToLat(0.3), sets[1].first, 1.0)
    }

    @Test
    fun `a drag that starts away from the pin is left for the room to scroll`() {
        picker()
        val field = compose.onNodeWithContentDescription("Pick a place on the map")
        val size = field.fetchSemanticsNode().size
        field.performTouchInput { click(Offset(size.width * 0.2f, size.height * 0.2f)) }
        settleTap()
        val before = sets.size
        // Far from the pin, and not a tap: no placement and no report.
        field.performTouchInput {
            swipe(Offset(size.width * 0.8f, size.height * 0.9f), Offset(size.width * 0.8f, size.height * 0.5f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(before, sets.size)
    }

    @Test
    fun `remove and write-into-file exist only for a placed location and keep their meaning`() {
        picker(latitude = 17.4435, longitude = 78.3772)
        compose.onNodeWithContentDescription("Chosen place", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Also write into the photo file").performClick()
        compose.onNodeWithText("Remove this location").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, written)
        assertEquals(1, cleared)
        assertEquals(1, done)
        assertTrue("those actions must not report a location", sets.isEmpty())
    }
}
