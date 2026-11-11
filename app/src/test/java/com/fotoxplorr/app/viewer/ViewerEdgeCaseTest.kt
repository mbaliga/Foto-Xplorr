package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.GeoMetadata
import com.fotoxplorr.app.spatial.MapFocus
import com.fotoxplorr.app.spatial.StampMapScreen
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer's four surfaces composed against every nullable path the brief names, so that
 * "crash-free" is a thing these tests prove rather than a thing the code assumes: a photo with no
 * EXIF, no GPS, zero dimensions; a file that cannot be decoded; an empty roll; a video; a room
 * opened mid-page-swipe; and a rotation under an open room. Each test composes the REAL
 * composables through the real Compose runtime; a throw anywhere in composition, layout, draw or
 * an effect fails it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerEdgeCaseTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    @Test
    fun `the details room survives a photo with no EXIF, no GPS and zero dimensions`() {
        val blank = ViewerFixtures.referencePhoto(context).copy(width = 0, height = 0, sizeBytes = 0L)
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = blank,
                    exif = ImageExifDetails(),
                    reveal = { 1f },
                    settled = true,
                    onSetCaption = {},
                    onAddTag = {},
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the details room survives an EXIF read that failed`() {
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = ImageExifDetails(readOk = false),
                    reveal = { 1f },
                    onSetLocation = { _, _ -> },
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a NaN or out-of-range coordinate draws the empty branch, not a plate`() {
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = ViewerFixtures.referencePhoto(context),
                    exif = ImageExifDetails(latitude = Double.NaN, longitude = 78.0),
                    reveal = { 1f },
                    manualLatitude = 200.0,
                    manualLongitude = 400.0,
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Where this photo was taken").assertDoesNotExist()
    }

    @Test
    fun `the details room survives a file that cannot be decoded`() {
        val broken = ViewerFixtures.referencePhoto(context).copy(
            contentUriString = "file:///definitely/not/here.jpg",
        )
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = broken,
                    exif = ViewerFixtures.referenceExif,
                    reveal = { 1f },
                    settled = true,
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the details room survives a video asset`() {
        val video = ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
        )
        compose.setContent {
            themed {
                PhotoDetailRoom(
                    asset = video,
                    exif = ImageExifDetails(readOk = true),
                    reveal = { 1f },
                    informationInitiallyExpanded = true,
                    onSetCaption = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the plate survives every reveal a finger can stop at`() {
        var reveal by mutableFloatStateOf(0f)
        compose.setContent {
            themed {
                PlacePlate(
                    asset = ViewerFixtures.referencePhoto(context),
                    latitude = 17.4435,
                    longitude = 78.3772,
                    reveal = { reveal },
                    settled = reveal >= 1f,
                    onExpand = {},
                )
            }
        }
        for (step in listOf(-0.2f, 0f, 0.1f, 0.45f, 0.5f, 0.85f, 1f, 1.3f)) {
            reveal = step
            compose.waitForIdle()
        }
    }

    @Test
    fun `the filmstrip survives an empty roll, a single photo and a roll that shrinks under it`() {
        var roll by mutableStateOf(ViewerFixtures.roll(context))
        var index by mutableStateOf(4)
        compose.setContent {
            themed {
                FilmstripScrubber(
                    assets = roll,
                    currentIndex = index,
                    onSelect = { picked -> index = roll.indexOf(picked) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        compose.waitForIdle()
        roll = roll.take(2)
        index = 7
        compose.waitForIdle()
        roll = roll.take(1)
        compose.waitForIdle()
        roll = emptyList()
        index = -1
        compose.waitForIdle()
    }

    @Test
    fun `the filmstrip reports live selection while being dragged and never out of range`() {
        val roll = ViewerFixtures.roll(context)
        val reported = mutableListOf<MediaAsset>()
        var index by mutableStateOf(4)
        compose.setContent {
            themed {
                FilmstripScrubber(
                    assets = roll,
                    currentIndex = index,
                    onSelect = { picked ->
                        reported += picked
                        index = roll.indexOf(picked)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        compose.waitForIdle()
        // ON the strip, which sits along the bottom edge -- not across the middle of the screen.
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX + 150f, bottom - 60f),
                end = Offset(centerX - 150f, bottom - 60f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        // Every report is a real member of the roll, and the strip did report something.
        assert(reported.all { it in roll }) { "reported an asset outside the roll" }
        assert(reported.isNotEmpty()) { "a drag across several thumbnails reported nothing" }
    }

    @Test
    fun `the minimap survives zero dimensions and a zero container`() {
        val blank = ViewerFixtures.referencePhoto(context).copy(width = 0, height = 0)
        compose.setContent {
            themed {
                ZoomMinimap(
                    asset = blank,
                    scale = { 3f },
                    offsetX = { 100f },
                    offsetY = { -4000f },
                    containerSize = IntSize(880, 1912),
                    modifier = Modifier.align(Alignment.BottomStart),
                )
                ZoomMinimap(
                    asset = blank,
                    scale = { 3f },
                    offsetX = { 0f },
                    offsetY = { 0f },
                    containerSize = IntSize.Zero,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the viewer survives a room pulled open mid-page-swipe and a rotation under it`() {
        val roll = ViewerFixtures.roll(context)
        var current by mutableStateOf(roll[4])
        // "Rotation" is a container that turns landscape under the live viewer: the same
        // re-layout a configuration change produces, without Robolectric recreating the activity
        // (which would also discard the composition and prove nothing about the viewer).
        var landscape by mutableStateOf(false)
        compose.setContent {
            themed {
                val frame = if (landscape) Modifier.size(956.dp, 440.dp) else Modifier.fillMaxSize()
                Box(frame) { ViewerScreen(
                    asset = current,
                    position = roll.indexOf(current) + 1,
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
                    onPrevious = { current = roll[3] },
                    onNext = { current = roll[5] },
                    onClose = {},
                    relatedAssets = roll,
                    onSelectAsset = { current = it },
                    plateMapBackdrop = ViewerFixtures.mapStandIn,
                ) }
            }
        }
        compose.waitForIdle()
        // Pull the details room down from the top edge (below the status bar strip)...
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        // ...and page while it is still settling, then again once open.
        current = roll[5]
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        current = roll[6]
        compose.waitForIdle()
        // Rotate underneath an open room.
        landscape = true
        current = roll[7]
        compose.waitForIdle()
        // And zoom afterwards: the minimap's maths against a landscape container.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        landscape = false
        compose.waitForIdle()
    }

    @Test
    fun `the viewer survives a video asset and an empty related list`() {
        val video = ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
        )
        compose.setContent {
            themed {
                ViewerScreen(
                    asset = video,
                    position = 1,
                    total = 1,
                    isFavorite = false,
                    isSensitive = false,
                    hasPrevious = false,
                    hasNext = false,
                    canMoveToTrash = false,
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
                    relatedAssets = emptyList(),
                    catalogueActions = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = centerY + 300f, durationMillis = 300) }
        compose.waitForIdle()
    }

    @Test
    fun `tapping the plate expands the map and Back collapses it, offline`() {
        // The connect flavour's expanded map is a MapLibre MapView, which has no JVM renderer;
        // the offline flavour's is the stamp map, which is pure Compose and runs here.
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        val roll = ViewerFixtures.roll(context)
        val geo = GeoIndexState(
            metadataById = roll.associate { it.id to GeoMetadata(it.id, 17.44 + it.id.value * 0.01, 78.37, null, null) },
            scannedCount = roll.size,
            totalCount = roll.size,
        )
        compose.setContent {
            themed {
                ViewerScreen(
                    asset = roll[4],
                    position = 5,
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
                    // The EXIF for a fixture file carries no GPS, so the plate is drawn from the
                    // hand-placed location -- the same path a pin dropped in the picker takes.
                    manualLatitude = 17.4835,
                    manualLongitude = 78.37,
                    geoState = geo,
                    libraryAssets = roll,
                    plateMapBackdrop = ViewerFixtures.mapStandIn,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown(startY = 20f, endY = bottom - 40f, durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Where this photo was taken").performClick()
        compose.waitForIdle()
        // The stamp map in focus mode draws the photo's own tile -- a second pin beside the
        // plate's, which stays composed in the room underneath the expanded map.
        val pins = compose.onAllNodesWithContentDescription("Photo location", useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assert(pins >= 2) { "expected the plate's pin and the map's pin, found $pins" }
        // Back collapses it.
        val activity = (compose as AndroidComposeTestRule<*, *>).activity
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `the stamp map in focus mode survives a focus the index does not know, and thousands of dots`() {
        val roll = ViewerFixtures.roll(context)
        val many = (100L..2100L).map { id -> roll[0].copy(id = MediaId(id)) }
        val geo = GeoIndexState(
            metadataById = many.associate { it.id to GeoMetadata(it.id, (it.id.value % 170) - 85.0, (it.id.value % 360) - 180.0, null, null) },
        )
        compose.setContent {
            themed {
                StampMapScreen(
                    assets = many + roll[4],
                    geoState = geo,
                    onIndexLocations = {},
                    onOpenAsset = { _, _ -> },
                    onClose = {},
                    focus = MapFocus(
                        roll[4].id, 17.4435, 78.3772,
                        dotColors = mapOf(MediaId(100L) to 0xFFFF0000.toInt()),
                        accentArgb = 0xFF5A8BFF.toInt(),
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Photo location").assertExists()
    }

    @Composable
    private fun themed(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        }
    }
}
