package com.fotoxplorr.app.gallery

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The browser with the map and the time range wired in: the bottom-left disc, the Back order, and
 * a range that outlives going to the map and back. The map itself is the offline flavour's stamp
 * map (pure Compose); the connect flavour's is a MapLibre view with no JVM renderer, so the tests
 * that put a map on screen skip there, as the viewer's do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryMapModeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.systemDefault()

    private fun taken(year: Int, month: Int, day: Int) =
        LocalDateTime.of(year, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()

    // Newest first, as the default sort shows them: three in 2025, then three in 2024.
    private val assets = listOf(
        1L to taken(2025, 8, 20), 2L to taken(2025, 6, 10), 3L to taken(2025, 2, 3),
        4L to taken(2024, 11, 5), 5L to taken(2024, 7, 9), 6L to taken(2024, 1, 15),
    ).map { (id, millis) ->
        MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/images/media/$id",
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = millis,
            dateModifiedSeconds = millis / 1_000L,
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun mount() {
        compose.setContent {
            FotoXplorrTheme(emptyState().preferences) {
                GalleryScreen(
                    state = emptyState().copy(assets = assets),
                    actions = noOpActions(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tilesShown() = (1L..6L).count {
        compose.onAllNodesWithContentDescriptionCompat("IMG_$it.jpg")
    }

    private fun AndroidComposeTestRule<*, *>.onAllNodesWithContentDescriptionCompat(description: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // ---- the disc ----

    @Test
    fun `the grid offers the map and the old Map pill is gone`() {
        mount()
        compose.onNodeWithContentDescription("Show map").assertExists()
        compose.onNodeWithContentDescription("Show grid").assertDoesNotExist()
        // The switcher keeps the views that are still on it; the map is not one of them.
        compose.onNodeWithText("GRID").assertExists()
        compose.onNodeWithText("CAL").assertExists()
        compose.onNodeWithText("TIME").assertExists()
        compose.onNodeWithText("MAP").assertDoesNotExist()
    }

    @Test
    fun `the disc swaps the grid for the map and back`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        org.junit.Assert.assertEquals(6, tilesShown())

        compose.onNodeWithContentDescription("Show map").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show grid").assertExists()
        org.junit.Assert.assertEquals("the grid is out of the way", 0, tilesShown())
        // The switcher describes the grid's views and is not drawn over the map.
        compose.onNodeWithText("CAL").assertDoesNotExist()

        compose.onNodeWithContentDescription("Show grid").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show map").assertExists()
        org.junit.Assert.assertEquals(6, tilesShown())
    }

    @Test
    fun `Back on the map returns to the grid`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        compose.onNodeWithContentDescription("Show map").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show grid").assertExists()

        back()
        compose.onNodeWithContentDescription("Show map").assertExists()
        org.junit.Assert.assertEquals(6, tilesShown())
    }

    @Test
    fun `with the map and the editor both up Back closes the editor first and then the map`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        compose.onNodeWithContentDescription("Show map").performClick()
        compose.waitForIdle()
        openEditor()
        compose.onNodeWithText("Time").assertExists()

        back()
        compose.onNodeWithText("Time").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show grid").assertExists()

        back()
        compose.onNodeWithContentDescription("Show map").assertExists()
    }

    // ---- the time range ----

    private fun openEditor() {
        compose.onNodeWithContentDescription("Filter by time").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a year chosen in the editor narrows the grid and shows a chip with the count`() {
        mount()
        org.junit.Assert.assertEquals(6, tilesShown())
        openEditor()
        compose.onNodeWithContentDescription("Show 2024").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("2024 · 3").assertExists()
        org.junit.Assert.assertEquals(3, tilesShown())
        compose.onNodeWithContentDescription("IMG_5.jpg").assertExists()
        compose.onNodeWithContentDescription("IMG_1.jpg").assertDoesNotExist()
    }

    @Test
    fun `clearing the chip brings everything back`() {
        mount()
        openEditor()
        compose.onNodeWithContentDescription("Show 2025").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(3, tilesShown())

        compose.onNodeWithContentDescription("Clear time range").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(6, tilesShown())
        compose.onNodeWithContentDescription("Clear time range").assertDoesNotExist()
    }

    @Test
    fun `Back closes the editor before anything else`() {
        mount()
        openEditor()
        compose.onNodeWithText("Time").assertExists()
        back()
        compose.onNodeWithText("Time").assertDoesNotExist()
    }

    @Test
    fun `the range survives going to the map and coming back`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        openEditor()
        compose.onNodeWithContentDescription("Show 2024").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2024 · 3").assertExists()

        compose.onNodeWithContentDescription("Show map").performClick()
        compose.waitForIdle()
        // Still on the chip over the map, with the same count: the map is fed the same photos.
        compose.onNodeWithText("2024 · 3").assertExists()

        compose.onNodeWithContentDescription("Show grid").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2024 · 3").assertExists()
        org.junit.Assert.assertEquals(3, tilesShown())
    }
}
