package com.fotoxplorr.app.gallery

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The browser with the photos scrolling sideways (owner #25: "left or right ... or top and bottom
 * with a horizontal scroll"): the photos run down a column and then on to the next, the latest end
 * is the left or the right by the sort, the stamp view stays vertical, and a strip along the bottom
 * or top still scrubs the grid and still has its gutter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryScrollAxisTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.systemDefault()

    private fun taken(year: Int, month: Int, day: Int) =
        LocalDateTime.of(year, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun photo(id: Long, millis: Long) = MediaAsset(
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

    // Three in August 2025, one in June 2025, two in November 2024.
    private val few = listOf(
        1L to taken(2025, 8, 20), 2L to taken(2025, 8, 12), 3L to taken(2025, 8, 3),
        4L to taken(2025, 6, 10), 5L to taken(2024, 11, 5), 6L to taken(2024, 11, 1),
    ).map { (id, millis) -> photo(id, millis) }

    private fun mount(
        assets: List<MediaAsset> = few,
        preferences: GalleryPreferencesState,
    ) {
        compose.setContent {
            FotoXplorrTheme(preferences) {
                GalleryScreen(
                    state = emptyState().copy(assets = assets, preferences = preferences),
                    actions = noOpActions(),
                )
            }
        }
        compose.waitForIdle()
    }

    private val gutterPx get() = 44 * compose.density.density

    private fun rootBounds(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun tileBounds(id: Long): Rect = compose
        .onAllNodes(hasContentDescription("IMG_$id.jpg"))
        .onFirst().fetchSemanticsNode().boundsInRoot

    private fun shown(id: Long) =
        compose.onAllNodes(hasContentDescription("IMG_$id.jpg")).fetchSemanticsNodes().isNotEmpty()

    private fun prefs(
        sort: GallerySort = GallerySort.NEWEST,
        placement: TimelinePlacement = TimelinePlacement.RIGHT,
        timelineOn: Boolean = false,
        rung: Int = 3,
        axis: ScrollAxis = ScrollAxis.HORIZONTAL,
    ) = GalleryPreferencesState(
        sort = sort,
        gridZoomRung = rung,
        timelineOn = timelineOn,
        timelinePlacement = placement,
        scrollAxis = axis,
    )

    // ---- the photos run down a column, then on to the next ----

    @Test
    fun `sideways, photos stack in a column and the next column follows to the right`() {
        mount(preferences = prefs())
        val first = tileBounds(1)
        val second = tileBounds(2)
        val third = tileBounds(3)
        val fourth = tileBounds(4)
        assertEquals(first.left, second.left, 1f)
        assertEquals(first.left, third.left, 1f)
        assertTrue(first.top < second.top && second.top < third.top)
        assertTrue("the fourth photo starts the next column", fourth.left > first.left)
        assertEquals(first.top, fourth.top, 1f)
    }

    @Test
    fun `sideways tiles are as wide as the tile aspect makes them of their height`() {
        mount(preferences = prefs())
        val tile = tileBounds(1)
        assertEquals(tile.height * GRID_TILE_ASPECT, tile.width, 2f)
    }

    @Test
    fun `newest first has the latest photo at the left`() {
        mount(preferences = prefs(sort = GallerySort.NEWEST))
        assertTrue(tileBounds(1).left < tileBounds(6).left)
        assertTrue("the latest sits at the left edge", tileBounds(1).left < 4f * compose.density.density)
    }

    @Test
    fun `the rungs are rows across`() {
        mount(preferences = prefs(rung = 2))
        // Two rows: the first two photos fill a column, the third starts the next.
        assertEquals(tileBounds(1).left, tileBounds(2).left, 1f)
        assertTrue(tileBounds(3).left > tileBounds(1).left)
    }

    // ---- the latest end ----

    private val many = (0L until 400L).map { photo(it, taken(2024, 1, 1) + it * 86_400_000L) }

    @Test
    fun `newest first opens at the left, on the newest photo`() {
        mount(many, prefs(sort = GallerySort.NEWEST))
        assertTrue(shown(399))
        assertFalse(shown(0))
    }

    @Test
    fun `oldest first opens at the right end, on the latest photo`() {
        mount(many, prefs(sort = GallerySort.OLDEST))
        assertTrue(shown(399))
        assertFalse(shown(0))
    }

    // ---- what stays vertical ----

    @Test
    fun `the stamp view stays vertical when sideways is chosen`() {
        mount(preferences = prefs(rung = 7))
        // Stamps fill a row side by side: the first two share a top, which no column would give.
        assertEquals(tileBounds(1).top, tileBounds(2).top, 1f)
    }

    @Test
    fun `masonry stays vertical when sideways is chosen`() {
        mount(preferences = prefs().copy(fitToTile = false))
        assertEquals(tileBounds(1).top, tileBounds(2).top, 1f)
    }

    // ---- a strip along the bottom or the top suits it ----

    @Test
    fun `a bottom strip leaves the sideways grid its own gutter`() {
        mount(preferences = prefs(timelineOn = true, placement = TimelinePlacement.BOTTOM))
        val bottom = (1L..6L).filter(::shown).maxOf { tileBounds(it).bottom }
        assertTrue(bottom <= rootBounds().bottom - gutterPx + 1f)
    }

    @Test
    fun `a top strip leaves the sideways grid its own gutter`() {
        mount(preferences = prefs(timelineOn = true, placement = TimelinePlacement.TOP))
        val top = (1L..6L).filter(::shown).minOf { tileBounds(it).top }
        assertTrue(top >= rootBounds().top + gutterPx - 1f)
    }

    @Test
    fun `dragging along the bottom strip scrubs the sideways grid`() {
        mount(many, prefs(timelineOn = true, placement = TimelinePlacement.BOTTOM))
        assertTrue(shown(399))
        val root = rootBounds()
        compose.onRoot().performTouchInput {
            val y = root.bottom - gutterPx / 2f
            down(Offset(root.width * 0.3f, y))
            moveTo(Offset(root.width * 0.6f, y))
            moveTo(Offset(root.width * 0.9f, y))
            up()
        }
        compose.waitForIdle()
        assertFalse("the grid followed the strip toward the oldest photos", shown(399))
    }
}
