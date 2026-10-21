package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
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
 * The place plate for a photo with no location, through the real [PhotoDetailRoom]: what it says,
 * when it offers to set a place, and above all when it does NOT (P0-02 in docs/TRAPS.md -- a
 * failed metadata read is not evidence the file has no GPS tag, so it must never lead to the
 * picker). Also the room's pin report, which is what the shrink-to-map flight lands on.
 *
 * These run on the JVM with no map in either flavour (MapLibre cannot render here), which is also
 * the offline flavour's whole story: the plate has to look right on its graticule alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PlaceUnknownPlateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    @Test
    fun `a photo with no location says so, shows a ghost pin and offers to add a place`() {
        room(exif = ImageExifDetails(readOk = true), onSetLocation = { _, _ -> })
        compose.onNodeWithText("No location yet").assertExists()
        compose.onNodeWithText("Add a place").assertExists().assertHasClickAction()
        compose.onNodeWithContentDescription("Photo, location unknown", useUnmergedTree = true).assertExists()
        // It is not a located plate and not a located pin.
        compose.onNodeWithContentDescription("Where this photo was taken").assertDoesNotExist()
        compose.onNodeWithContentDescription("Photo location", useUnmergedTree = true).assertDoesNotExist()
        // Record-only picture for the owner's eye: build/renders/place-unknown-add.png.
        compose.onRoot().captureRoboImage("build/renders/place-unknown-add.png")
    }

    @Test
    fun `the old searching eyebrow and any sign of a spinning map are gone`() {
        room(exif = ImageExifDetails(readOk = true), onSetLocation = { _, _ -> })
        compose.onNodeWithText("NO LOCATION YET").assertDoesNotExist()
        // Nothing animates on the plate: an infinite animation would keep the clock from ever
        // idling, so reaching idle here is the assertion.
        compose.waitForIdle()
    }

    @Test
    fun `without a way to set a place it explains the absence and offers no button`() {
        room(exif = ImageExifDetails(readOk = true), onSetLocation = null)
        compose.onNodeWithText("No location yet").assertExists()
        compose.onNodeWithText("Add a place").assertDoesNotExist()
        compose.onNodeWithText("Chat apps and websites usually remove location.").assertExists()
    }

    @Test
    fun `after a failed read it never offers the picker - P0-02`() {
        room(exif = ImageExifDetails(readOk = false), onSetLocation = { _, _ -> })
        compose.onNodeWithText("Add a place").assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pick a place on the map").assertDoesNotExist()
        compose.onNodeWithText("Couldn't read this photo's details").assertExists()
    }

    @Test
    fun `a photo with an embedded location keeps its real plate and offers no add`() {
        room(exif = ViewerFixtures.referenceExif, onSetLocation = { _, _ -> })
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
        compose.onNodeWithText("Add a place").assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertDoesNotExist()
        // A real GPS tag is not "placed by you", and is not offered for change.
        compose.onNodeWithText("Placed by you", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a nonsense coordinate is the unknown plate, not a plate drawn on nonsense`() {
        room(exif = ImageExifDetails(latitude = Double.NaN, longitude = 78.0, readOk = true), onSetLocation = null)
        compose.onNodeWithContentDescription("Where this photo was taken").assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertExists()
    }

    @Test
    fun `add a place opens the picker with no pin, and done closes it again`() {
        room(exif = ImageExifDetails(readOk = true), onSetLocation = { _, _ -> })
        compose.onNodeWithText("Add a place").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pick a place on the map").assertExists()
        // The old picker drew a pin at 0 N 0 E before anyone had touched anything.
        compose.onNodeWithContentDescription("Chosen place", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/renders/place-picker-empty.png")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No location yet").assertExists()
        compose.onNodeWithContentDescription("Pick a place on the map").assertDoesNotExist()
    }

    @Test
    fun `a hand-placed location is a real plate with a way back into the picker`() {
        room(
            exif = ImageExifDetails(readOk = true),
            manualLatitude = 17.4835,
            manualLongitude = 78.37,
            onSetLocation = { _, _ -> },
        )
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
        compose.onNodeWithText("Placed by you", substring = true).assertExists().performClick()
        compose.waitForIdle()
        // The picker, now with the place it was given: pin drawn, remove offered.
        compose.onNodeWithContentDescription("Chosen place", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Remove this location").assertExists()
    }

    @Test
    fun `the room reports the pin tile once in its own coordinates, whatever the reveal`() {
        var reveal by mutableFloatStateOf(0f)
        val reports = mutableListOf<Rect>()
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = ImageExifDetails(readOk = true),
                    reveal = { reveal },
                    flightActive = true,
                    onPinPositioned = { reports += it },
                )
            }
        }
        for (step in listOf(0.2f, 0.5f, 0.9f, 1f, 0.4f, 0f)) {
            reveal = step
            compose.waitForIdle()
        }
        assertTrue("the pin was never reported", reports.isNotEmpty())
        // 80 dp at 2x, all the same rectangle: the room does not move in this harness, and in the
        // shell it moves rigidly, which room coordinates cancel.
        reports.forEach { assertEquals(160f, it.width, 0.5f) }
        assertEquals("a steady room must report once", 1, reports.size)
    }

    @Test
    fun `the plate survives every flight mode at every reveal a finger can stop at`() {
        var reveal by mutableFloatStateOf(0f)
        var active by mutableStateOf(false)
        var flies by mutableStateOf(true)
        compose.setContent {
            themed {
                PlaceUnknownPlate(
                    asset = ViewerFixtures.referencePhoto(context),
                    reveal = { reveal },
                    onAddPlace = {},
                    flightActive = active,
                    flightFlies = flies,
                )
            }
        }
        for ((a, f) in listOf(false to true, true to true, true to false)) {
            active = a
            flies = f
            for (step in listOf(-0.2f, 0f, 0.1f, 0.45f, 0.95f, 0.97f, 1f, 1.3f)) {
                reveal = step
                compose.waitForIdle()
            }
        }
    }

    private fun room(
        exif: ImageExifDetails,
        onSetLocation: ((Double, Double) -> Unit)? = null,
        manualLatitude: Double? = null,
        manualLongitude: Double? = null,
    ) {
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = exif,
                    reveal = { 1f },
                    settled = true,
                    manualLatitude = manualLatitude,
                    manualLongitude = manualLongitude,
                    onSetLocation = onSetLocation,
                    onClearLocation = {},
                    informationInitiallyExpanded = false,
                )
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun themed(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }
}
