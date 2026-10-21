package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.FilmstripScrubber
import com.fotoxplorr.app.viewer.PhotoDetailRoom
import com.fotoxplorr.app.viewer.ViewerScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Compose's double-tap window, with a margin: a single tap fires only once it has passed. */
private const val DOUBLE_TAP_TIMEOUT_MILLIS = 700L

/**
 * The four viewer surfaces of the design-fidelity round, rendered the way [ScreenRenderTest]
 * renders everything else -- real composables, real Compose runtime, no device -- and, unlike the
 * earlier render tests, with REAL pixels in the photos (see [ViewerFixtures]), so each PNG under
 * `app/build/renders/` can be measured against the owner's reference screenshots:
 *
 * - `viewer-1-filmstrip` against 5.png: the photo centred, the resting 74 dp strip.
 * - `viewer-2-filmstrip-dragging`: the strip bulged, as it is under a moving finger.
 * - `viewer-3-details-open` against 6.png: the room fully open, a photo with a location, a
 *   stand-in under the pin where MapLibre cannot render on the JVM.
 * - `viewer-4-details-mid`: the room half open, the photo mid-flight into its pin.
 * - `viewer-5-details-no-location`: the empty-space branch.
 * - `viewer-6-minimap`: the photo zoomed, the navigator bottom-left.
 *
 * Same configuration as the other render classes, and the same reason for it: `sdk = 34` because
 * Robolectric has no runtime for 36, NATIVE graphics because the legacy mode draws nothing. The
 * qualifiers are the references' own 440 x 956 dp, at 2x, so 1 dp of the reference is 2 px here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    private fun render(name: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Test
    fun `viewer with the resting filmstrip`() {
        val roll = ViewerFixtures.roll(context)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    viewer(asset = roll[4], roll = roll)
                }
            }
        }
        compose.waitForIdle()
        // The chrome -- and with it the strip -- starts hidden; one tap on the photo shows it.
        // The tap detector also listens for a double tap, so it only fires after that timeout
        // has passed on the test clock.
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_TIMEOUT_MILLIS)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/renders/viewer-1-filmstrip.png")
    }

    @Test
    fun `filmstrip bulged mid-drag`() {
        val roll = ViewerFixtures.roll(context)
        render("viewer-2-filmstrip-dragging") {
            MediaImage(asset = roll[4], modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            FilmstripScrubber(
                assets = roll,
                currentIndex = 4,
                onSelect = {},
                modifier = Modifier.align(Alignment.BottomCenter),
                initialBulge = 1f,
            )
        }
    }

    @Test
    fun `details room fully open with a location`() {
        render("viewer-3-details-open") {
            PhotoDetailRoom(
                asset = ViewerFixtures.referencePhoto(context),
                exif = ViewerFixtures.referenceExif,
                reveal = { 1f },
                settled = true,
                plateMapBackdrop = ViewerFixtures.mapStandIn,
                onSetCaption = {},
                onAddTag = {},
                onExpandMap = {},
            )
        }
    }

    @Test
    fun `details room half open with the photo mid-flight`() {
        render("viewer-4-details-mid") {
            PhotoDetailRoom(
                asset = ViewerFixtures.referencePhoto(context),
                exif = ViewerFixtures.referenceExif,
                reveal = { 0.5f },
                settled = false,
                plateMapBackdrop = ViewerFixtures.mapStandIn,
                onSetCaption = {},
                onAddTag = {},
            )
        }
    }

    @Test
    fun `details room for a photo with no location`() {
        render("viewer-5-details-no-location") {
            PhotoDetailRoom(
                asset = ViewerFixtures.referencePhoto(context),
                exif = ViewerFixtures.referenceExif.copy(latitude = null, longitude = null),
                reveal = { 1f },
                settled = true,
                onSetCaption = {},
                onAddTag = {},
            )
        }
    }

    @Test
    fun `viewer zoomed in with the minimap`() {
        val roll = ViewerFixtures.roll(context)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    viewer(asset = roll[4], roll = roll)
                }
            }
        }
        compose.waitForIdle()
        // A double tap is the viewer's own zoom-in; the navigator appears only once zoomed.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/renders/viewer-6-minimap.png")
    }

    @Composable
    private fun viewer(asset: MediaAsset, roll: List<MediaAsset>) {
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
            relatedAssets = roll,
            plateMapBackdrop = ViewerFixtures.mapStandIn,
        )
    }
}
