package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composed halves of the shrink-to-map review fixes, through the real [PhotoDetailRoom]:
 * the place block before the metadata has been read (it must claim nothing), the pin's "gone"
 * report when the picker replaces the plate, and the EXIF reader's handling of orientation and of
 * a 0.0, 0.0 fix. The pure rules are in [ViewerShrinkPolicyTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerShrinkRoomTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    // ── the loading state is not "no location" ──────────────────────────────────────────────

    @Test
    fun `before the read lands the place block offers nothing and reports no pin`() {
        var exif by mutableStateOf(ImageExifDetails.PENDING)
        val reports = mutableListOf<Rect>()
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = exif,
                    reveal = { 1f },
                    settled = true,
                    onSetLocation = { _, _ -> },
                    onClearLocation = {},
                    flightActive = true,
                    onPinPositioned = { reports += it },
                )
            }
        }
        compose.waitForIdle()
        // None of the faces that make a claim about the file.
        compose.onNodeWithText("Add a place").assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertDoesNotExist()
        compose.onNodeWithText("Couldn't read this photo's details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Photo, location unknown", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Where this photo was taken").assertDoesNotExist()
        assertTrue("a pin was reported while loading: $reports", reports.isEmpty())

        // The read lands with no GPS: now, and only now, the unknown plate and its action.
        exif = ImageExifDetails(readOk = true)
        compose.waitForIdle()
        compose.onNodeWithText("No location yet").assertExists()
        compose.onNodeWithText("Add a place").assertExists()
        assertEquals(1, reports.size)
    }

    @Test
    fun `a geotagged file never shows the hand-placement offer on the way to its plate`() {
        var exif by mutableStateOf(ImageExifDetails.PENDING)
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = exif,
                    reveal = { 1f },
                    settled = true,
                    onSetLocation = { _, _ -> },
                    onClearLocation = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Add a place").assertDoesNotExist()
        exif = ImageExifDetails(latitude = 17.4435, longitude = 78.3772, readOk = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Where this photo was taken").assertExists()
        compose.onNodeWithText("Add a place").assertDoesNotExist()
    }

    // ── the pin says when it is gone ────────────────────────────────────────────────────────

    @Test
    fun `opening the picker over a hand-placed plate reports the pin gone, and back reports it again`() {
        val events = mutableListOf<String>()
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = ImageExifDetails(readOk = true),
                    reveal = { 1f },
                    settled = true,
                    manualLatitude = 17.4835,
                    manualLongitude = 78.37,
                    onSetLocation = { _, _ -> },
                    onClearLocation = {},
                    flightActive = true,
                    onPinPositioned = { events += "at" },
                    onPinGone = { events += "gone" },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("at"), events)

        compose.onNodeWithText("Placed by you", substring = true).performClick()
        compose.waitForIdle()
        assertEquals("the picker took the pin away", listOf("at", "gone"), events)

        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        // The plate is back at the very same place: a de-duplicating reporter must not swallow it.
        assertEquals(listOf("at", "gone", "at"), events)
    }

    @Test
    fun `a steady plate never reports the pin gone`() {
        var gone = 0
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = ImageExifDetails(readOk = true),
                    reveal = { 1f },
                    settled = true,
                    flightActive = true,
                    onPinGone = { gone++ },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, gone)
    }

    // ── what the reader makes of a real file ────────────────────────────────────────────────

    private fun jpegWith(name: String, configure: ExifInterface.() -> Unit): ExifInterface {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            configure()
            saveAttributes()
        }
        return ExifInterface(file.absolutePath)
    }

    @Test
    fun `a 0, 0 fix is no fix`() {
        val exif = jpegWith("null-island.jpg") { setLatLong(0.0, 0.0) }
        // The premise: the file does carry a GPS pair, and the library hands it back as 0, 0.
        val raw = exif.latLong
        assertNotNull("the fixture has no GPS pair", raw)
        assertEquals(0.0, raw!![0], 0.0)
        assertEquals(0.0, raw[1], 0.0)

        val details = exifDetailsFrom(exif)
        assertNull(details.latitude)
        assertNull(details.longitude)
        // So the room sees a photo with no place, whichever of its consumers asks.
        assertTrue(!PlaceMorph.isDrawable(details.latitude, details.longitude))
    }

    @Test
    fun `a real fix survives, as does a fix on one axis only`() {
        val real = exifDetailsFrom(jpegWith("real.jpg") { setLatLong(17.4435, 78.3772) })
        assertEquals(17.4435, real.latitude!!, 1e-4)
        assertEquals(78.3772, real.longitude!!, 1e-4)
        val equator = exifDetailsFrom(jpegWith("equator.jpg") { setLatLong(0.0, 78.3772) })
        assertEquals(0.0, equator.latitude!!, 1e-9)
        assertEquals(78.3772, equator.longitude!!, 1e-4)
    }

    @Test
    fun `the orientation tag is read, and left at zero when the file has none`() {
        val rotated = exifDetailsFrom(
            jpegWith("rotated.jpg") { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) },
        )
        assertEquals(6, rotated.orientation)
        assertEquals(0, exifDetailsFrom(jpegWith("plain.jpg") {}).orientation)
    }

    @Composable
    private fun themed(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }
}
