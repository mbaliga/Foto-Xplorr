package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.curate.ArchiveAdvisor
import com.fotoxplorr.app.curate.ArchiveReviewItem
import com.fotoxplorr.app.curate.ArchiveSuggestionsReview
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ImageExifDetails
import com.fotoxplorr.app.viewer.KeyMomentBar
import com.fotoxplorr.app.viewer.MomentScrubber
import com.fotoxplorr.app.viewer.PhotoDetailRoom
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The surfaces added in the key-moments / lens / background-rules / auto-curation round, rendered
 * the same way [ScreenRenderTest] renders the shade: real composables, real Compose runtime, no
 * device. Same configuration and the same reason for it — see that class's own KDoc.
 *
 * Photos themselves render as empty tiles here (Coil cannot decode a `content://` URI on the JVM),
 * so each capture is arranged so that the chrome under test carries the picture: chips, a caption
 * field, a menu, a scrubber, a rule builder. That is what the owner asked to see, and what the
 * code either does or does not draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class NewSurfaceRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(name: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    /** The details room with everything a photo can now carry: EXIF, recognised text, tags, caption. */
    @Test
    fun `details room with tags caption and lens card`() {
        render("details-1-tags-caption-lens") {
            PhotoDetailRoom(
                asset = photo(id = 1, name = "IMG_20250614_091233.jpg"),
                exif = ImageExifDetails(
                    make = "Google",
                    model = "Pixel 9 Pro",
                    lensModel = "Pixel 9 Pro back camera 6.9mm f/1.68",
                    focalLengthMm = 6.9,
                    aperture = 1.68,
                    iso = "64",
                    shutterSpeed = "1/250",
                    exposureBiasEv = 0.0,
                    flash = 0,
                    colorSpace = "sRGB",
                ),
                reveal = { 1f },
                isFavorite = true,
                recognizedText = "FARMERS MARKET\nSaturdays 8am to 1pm\nLocal honey · Fresh bread",
                onSearchLibrary = {},
                tags = setOf("market", "weekend", "bread", "outdoor", "food"),
                autoTags = setOf("bread", "outdoor", "food"),
                onRemoveTag = {},
                caption = "A sunny morning at a farmers market with fresh bread on display",
                captionIsMachineWritten = true,
                onSetCaption = {},
            )
        }
    }

    /** The "Key moment" pill with its menu open, over the segmented scrubber, as in the reference. */
    @Test
    fun `key moment pill with menu open over the scrubber`() {
        val video = MediaId(7L)
        val moments = listOf(
            VideoMoment(video, 4_200L, MomentSource.AUTO, confidence = 0.62f, label = "Scene change"),
            VideoMoment(video, 18_500L, MomentSource.MANUAL),
            VideoMoment(video, 31_000L, MomentSource.AUTO, confidence = 0.81f, label = "Brightness shift"),
        )
        render("moments-1-pill-menu-scrubber") {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeyMomentBar(
                    activeMoment = moments[0],
                    positionMs = 4_260L,
                    durationMs = 45_000L,
                    feedback = null,
                    onShareMoment = {},
                    onCreateClip = {},
                    onRemoveMarker = {},
                    onFeedback = { _, _ -> },
                    initiallyExpanded = true,
                )
                MomentScrubber(
                    positionMs = 4_260L,
                    durationMs = 45_000L,
                    moments = moments,
                    onSeek = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }

    /** The rule builder: every value the owner asked to be writable, plus the RIGHT NOW readout. */
    @Test
    fun `settings background rules tab`() {
        render("settings-1-background-rules") {
            SettingsTabsRoom(
                state = emptyState(),
                actions = noOpActions(),
                onOpenLegacyScreen = {},
                onOpenSupport = {},
                onOpenMoreApps = {},
                initialTab = SettingsTab.BACKGROUND,
            )
        }
    }

    /** Where "Tidy up" is reached from. */
    @Test
    fun `settings library tab with tidy up entry`() {
        render("settings-2-library-tidy-up") {
            SettingsTabsRoom(
                state = emptyState(),
                actions = noOpActions(),
                onOpenLegacyScreen = {},
                onOpenSupport = {},
                onOpenMoreApps = {},
                initialTab = SettingsTab.LIBRARY,
            )
        }
    }

    /**
     * The archive review queue, fed through the REAL advisor rather than hand-built suggestions, so
     * the render also proves the three cheap signals fire: an exact duplicate, an old screenshot and
     * a tiny image.
     */
    @Test
    fun `tidy up review queue`() {
        val assets = listOf(
            photo(id = 11, name = "IMG_20250301_120000.jpg", ageDays = 30, sizeBytes = 4_000_000L, width = 4000, height = 3000),
            photo(id = 12, name = "IMG_20250301_120000 (1).jpg", ageDays = 29, sizeBytes = 4_000_000L, width = 4000, height = 3000),
            photo(id = 13, name = "Screenshot_20250101-081500.png", ageDays = 240, sizeBytes = 800_000L, width = 1080, height = 2400, mime = "image/png"),
            photo(id = 14, name = "thumb_avatar.jpg", ageDays = 10, sizeBytes = 12_000L, width = 200, height = 150),
        )
        val candidates = assets.map { asset ->
            ArchiveAdvisor.ArchiveCandidate(
                mediaId = asset.id,
                isFavorite = false,
                isArchived = false,
                previouslyDismissed = false,
                isScreenshot = asset.displayName.startsWith("Screenshot"),
                ageMillis = NOW_MILLIS - asset.dateTakenMillis,
                sizeBytes = asset.sizeBytes,
                widthPx = asset.width,
                heightPx = asset.height,
                mimeType = asset.mimeType,
            )
        }
        val items = ArchiveAdvisor.suggestions(candidates).mapNotNull { suggestion ->
            assets.firstOrNull { it.id == suggestion.mediaId }?.let { ArchiveReviewItem(suggestion, it) }
        }
        check(items.isNotEmpty()) { "The advisor offered nothing for a fixture built to trip all three signals" }
        render("tidy-up-1-review") {
            ArchiveSuggestionsReview(items = items, onAccept = {}, onReject = {})
        }
    }

    private fun photo(
        id: Long,
        name: String,
        ageDays: Int = 3,
        sizeBytes: Long = 3_200_000L,
        width: Int = 4032,
        height: Int = 3024,
        mime: String = "image/jpeg",
    ): MediaAsset {
        val taken = NOW_MILLIS - ageDays.toLong() * 86_400_000L
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/images/media/$id",
            displayName = name,
            mimeType = mime,
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1_000L,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private companion object {
        /** A fixed "now" so the renders and the advisor's age arithmetic are deterministic. */
        const val NOW_MILLIS = 1_756_684_800_000L // 2025-09-01T00:00:00Z
    }
}
