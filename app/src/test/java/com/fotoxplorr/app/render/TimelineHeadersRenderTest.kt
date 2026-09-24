package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.TimelineGrouping
import com.fotoxplorr.app.gallery.TimelineScreen
import com.fotoxplorr.app.gallery.timelineGroups
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P0-15: `TimelineScreen`'s date-header branch, reached directly from `GalleryScreen` now that
 * `timelineHeadersOn` calls it (see docs/agent handoff and GridIndexMapTest for the index-mapping
 * coverage). This just proves the composable pipeline draws real header rows over real groups
 * without crashing once `groups` is supplied externally rather than computed inside -- same
 * rendering approach as [ScreenRenderTest]/[NewSurfaceRenderTest]: real Compose, no device,
 * photos as empty tiles since Coil cannot decode a `content://` URI on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class TimelineHeadersRenderTest {

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

    @Test
    fun `timeline with day headers renders one header row per day`() {
        val assets = listOf(
            asset(1, dayOffset = 0),
            asset(2, dayOffset = 0),
            asset(3, dayOffset = 1),
            asset(4, dayOffset = 2),
            asset(5, dayOffset = 2),
            asset(6, dayOffset = 2),
        )
        val groups = timelineGroups(assets, TimelineGrouping.DAY)
        check(groups.size == 3) { "Fixture should produce 3 day groups, got ${groups.size}" }
        render("timeline-1-day-headers") {
            TimelineScreen(
                assets = assets,
                groups = groups,
                grouping = TimelineGrouping.DAY,
                columns = 3,
                favoriteIds = emptySet(),
                sensitiveIds = emptySet(),
                blurSensitive = false,
                selectedIds = emptySet(),
                onOpen = {},
                onToggleSelection = {},
                showDateHeaders = true,
            )
        }
    }

    private fun asset(id: Long, dayOffset: Int): MediaAsset {
        val taken = NOW_MILLIS - dayOffset.toLong() * 86_400_000L
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/images/media/$id",
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = taken,
            dateModifiedSeconds = taken / 1_000L,
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private companion object {
        const val NOW_MILLIS = 1_756_684_800_000L // 2025-09-01T00:00:00Z
    }
}
