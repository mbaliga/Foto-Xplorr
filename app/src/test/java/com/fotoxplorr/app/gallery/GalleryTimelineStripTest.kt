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
 * The browser with the timeline wired in (owner #25): the grid makes room for the strip instead of
 * sitting under it, on whichever edge the strip is on, and only while the strip is there; month
 * headers appear with the timeline; and the Oldest first order opens at its latest end.
 *
 * The strip itself is the library's composable and is not looked at here -- its gutter is, because
 * that is the app's half: where the photos are, and where the floating controls are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryTimelineStripTest {

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
        preferences: GalleryPreferencesState = GalleryPreferencesState(),
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

    private fun shownTiles(ids: LongRange = 1L..6L): List<Rect> = ids.filter {
        compose.onAllNodes(hasContentDescription("IMG_$it.jpg")).fetchSemanticsNodes().isNotEmpty()
    }.map(::tileBounds)

    private fun prefs(
        placement: TimelinePlacement = TimelinePlacement.RIGHT,
        on: Boolean = true,
        sort: GallerySort = GallerySort.NEWEST,
        grouping: TimelineGrouping = TimelineGrouping.MONTH,
        fitToTile: Boolean = true,
    ) = GalleryPreferencesState(
        sort = sort,
        timelineOn = on,
        timelinePlacement = placement,
        timelineGrouping = grouping,
        fitToTile = fitToTile,
    )

    // ---- the gutter, edge by edge ----

    @Test
    fun `the grid stops short of a strip on the right`() {
        mount(preferences = prefs(TimelinePlacement.RIGHT))
        val tiles = shownTiles()
        assertEquals(6, tiles.size)
        assertTrue(tiles.maxOf { it.right } <= rootBounds().right - gutterPx + 1f)
    }

    @Test
    fun `the grid starts after a strip on the left`() {
        mount(preferences = prefs(TimelinePlacement.LEFT))
        val tiles = shownTiles()
        assertEquals(6, tiles.size)
        assertTrue(tiles.minOf { it.left } >= rootBounds().left + gutterPx - 1f)
    }

    @Test
    fun `the grid starts below a strip along the top`() {
        mount(preferences = prefs(TimelinePlacement.TOP))
        assertTrue(shownTiles().minOf { it.top } >= rootBounds().top + gutterPx - 1f)
        // The headers are inside the gutter's edge too, not under the strip.
        assertTrue(compose.onNodeWithText("August 2025").fetchSemanticsNode().boundsInRoot.top >= gutterPx - 1f)
    }

    private fun discBottom() =
        compose.onNodeWithContentDescription("Show map").fetchSemanticsNode().boundsInRoot.bottom

    @Test
    fun `the floating controls sit on the bottom margin when the strip is elsewhere`() {
        mount(preferences = prefs(TimelinePlacement.RIGHT))
        val margin = 20 * compose.density.density
        assertEquals(rootBounds().bottom - margin, discBottom(), 1f)
    }

    @Test
    fun `a strip along the bottom lifts the floating controls by its thickness`() {
        mount(preferences = prefs(TimelinePlacement.BOTTOM))
        val margin = 20 * compose.density.density
        assertEquals(rootBounds().bottom - margin - gutterPx, discBottom(), 1f)
    }

    // ---- no strip, no gutter ----

    @Test
    fun `with the timeline off the photos use the whole width`() {
        mount(preferences = prefs(on = false))
        val tiles = shownTiles()
        assertEquals(6, tiles.size)
        assertTrue(tiles.maxOf { it.right } > rootBounds().right - gutterPx)
        assertTrue(tiles.minOf { it.left } < gutterPx)
    }

    @Test
    fun `name order has no strip and so no gutter`() {
        mount(preferences = prefs(sort = GallerySort.NAME))
        assertTrue(shownTiles().maxOf { it.right } > rootBounds().right - gutterPx)
    }

    @Test
    fun `masonry has no strip and so no gutter`() {
        mount(preferences = prefs(fitToTile = false))
        assertTrue(shownTiles().maxOf { it.right } > rootBounds().right - gutterPx)
    }

    // ---- the headers that come with the timeline ----

    @Test
    fun `month headers separate the photos while the timeline is on`() {
        mount(preferences = prefs())
        compose.onNodeWithText("August 2025").assertExists()
        compose.onNodeWithText("June 2025").assertExists()
        compose.onNodeWithText("November 2024").assertExists()
    }

    @Test
    fun `with the timeline off the column rungs are a plain mosaic`() {
        mount(preferences = prefs(on = false))
        compose.onNodeWithText("August 2025").assertDoesNotExist()
    }

    @Test
    fun `no grouping means no headers even with the timeline on`() {
        mount(preferences = prefs(grouping = TimelineGrouping.NONE))
        compose.onNodeWithText("August 2025").assertDoesNotExist()
    }

    @Test
    fun `name order draws no headers on the column rungs`() {
        mount(preferences = prefs(sort = GallerySort.NAME))
        compose.onNodeWithText("August 2025").assertDoesNotExist()
    }

    // ---- opening at the latest end ----

    private val many = (0L until 400L).map { photo(it, taken(2024, 1, 1) + it * 86_400_000L) }

    @Test
    fun `newest first opens on the newest photo`() {
        mount(many, prefs(sort = GallerySort.NEWEST, on = false))
        compose.onNodeWithContentDescription("IMG_399.jpg").assertExists()
        compose.onNodeWithContentDescription("IMG_0.jpg").assertDoesNotExist()
    }

    @Test
    fun `oldest first opens at its end, on the latest photo`() {
        mount(many, prefs(sort = GallerySort.OLDEST, on = false))
        compose.onNodeWithContentDescription("IMG_399.jpg").assertExists()
        compose.onNodeWithContentDescription("IMG_0.jpg").assertDoesNotExist()
    }

    // ---- the strip and the rooms share an edge ----

    private fun swipe(fromX: Float, toX: Float, y: Float) {
        compose.onRoot().performTouchInput {
            down(Offset(fromX, y))
            moveTo(Offset((fromX + toX) / 2f, y))
            moveTo(Offset(toX, y))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a horizontal drag from the left edge still opens the rail under a left strip`() {
        mount(preferences = prefs(TimelinePlacement.LEFT))
        val root = rootBounds()
        // Inside the strip's own 44dp, so this is a drag that starts ON the strip.
        val fromX = gutterPx / 2f
        compose.onNodeWithText("Favourites").assertDoesNotExist()
        swipe(fromX, root.width * 0.8f, root.height / 2f)
        compose.onNodeWithText("Favourites").assertExists()
    }

    @Test
    fun `a horizontal drag from the right edge still opens the view options under a right strip`() {
        mount(preferences = prefs(TimelinePlacement.RIGHT))
        val root = rootBounds()
        compose.onNodeWithText("ARRANGE").assertDoesNotExist()
        swipe(root.right - gutterPx / 2f, root.width * 0.2f, root.height / 2f)
        compose.onNodeWithText("ARRANGE").assertExists()
    }

    @Test
    fun `dragging down the strip scrolls the grid to the oldest photos`() {
        mount(many, prefs(sort = GallerySort.NEWEST))
        compose.onNodeWithContentDescription("IMG_0.jpg").assertDoesNotExist()
        val root = rootBounds()
        compose.onRoot().performTouchInput {
            val x = root.right - gutterPx / 2f
            down(Offset(x, root.height * 0.3f))
            moveTo(Offset(x, root.height * 0.6f))
            moveTo(Offset(x, root.height * 0.97f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("IMG_399.jpg").assertDoesNotExist()
    }
}
