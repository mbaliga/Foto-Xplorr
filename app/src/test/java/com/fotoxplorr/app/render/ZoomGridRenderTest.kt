package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import com.fotoxplorr.app.gallery.GRID_TILE_ASPECT
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySort
import com.fotoxplorr.app.gallery.GridChromeBridge
import com.fotoxplorr.app.gallery.LayoutAnchor
import com.fotoxplorr.app.gallery.LocalGridChromeBridge
import com.fotoxplorr.app.gallery.SyntheticCatalogue
import com.fotoxplorr.app.gallery.ZoomGrid
import com.fotoxplorr.app.gallery.sortAssets
import com.fotoxplorr.app.gallery.stampHeadersShown
import com.fotoxplorr.app.gallery.zoomLayoutFor
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.organize.GridIndexMap
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one zoomable grid at its rungs -- 1, 3 and 6 columns and the stamp view -- over the synthetic
 * catalogue, drawn by the real [ZoomGrid]: the composable pipeline draws real tiles, stamps and
 * month headers without crashing, headers appear only at the stamps, and a pinch reaches the
 * zoom callbacks at every one of those rungs.
 *
 * Same rendering approach as [ScreenRenderTest]: real Compose, no device, photos as empty tiles
 * since Coil cannot decode a `content://` URI on the JVM. What the stamps' dotted edge or the
 * pinch feels like on a thumb is device-checked, not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ZoomGridRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // Newest first, as the grid is by default; the synthetic catalogue's undated photos fall back
    // to their modified time, so the same month can head more than one run.
    private val assets = sortAssets(SyntheticCatalogue.assets(1_500), GallerySort.NEWEST)

    private val bridge = GridChromeBridge()

    @Composable
    private fun Grid(
        rung: Int,
        horizontal: Boolean = false,
        headers: Boolean = stampHeadersShown(rung, GallerySort.NEWEST, masonry = false),
        state: LazyGridState = rememberLazyGridState(),
        anchor: LayoutAnchor = remember { LayoutAnchor() },
    ) {
        val layout = zoomLayoutFor(assets, headers = headers)
        ZoomGrid(
            layout = layout,
            rung = rung,
            favoriteIds = emptySet(),
            sensitiveIds = emptySet(),
            blurSensitive = false,
            selectedIds = emptySet(),
            selectionActive = false,
            emptyMessage = "none",
            onOpen = {},
            onToggleSelection = {},
            gridState = state,
            fitToTile = true,
            loopAnimations = false,
            animatedIds = emptySet(),
            anchor = anchor,
            horizontal = horizontal,
        )
    }

    private fun render(
        name: String?,
        rung: Int,
        horizontal: Boolean = false,
        headers: Boolean? = null,
        state: LazyGridState? = null,
    ) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Grid(
                            rung = rung,
                            horizontal = horizontal,
                            headers = headers ?: stampHeadersShown(rung, GallerySort.NEWEST, masonry = false),
                            state = state ?: rememberLazyGridState(),
                        )
                    }
                }
            }
        }
        if (name != null) compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    private val headings = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `one column renders without headers`() {
        render("zoom-1-one-column", 1)
        compose.onAllNodes(headings).assertCountEquals(0)
    }

    @Test
    fun `three columns render without headers`() {
        render("zoom-2-three-columns", 3)
        compose.onAllNodes(headings).assertCountEquals(0)
    }

    @Test
    fun `six columns render without headers`() {
        render("zoom-3-six-columns", 6)
        compose.onAllNodes(headings).assertCountEquals(0)
    }

    @Test
    fun `the stamp view renders a header row per month on screen`() {
        render("zoom-4-stamps", ZOOM_RUNG_STAMPS)
        val shown = compose.onAllNodes(headings).fetchSemanticsNodes().size
        assertTrue("expected month headers on the first screen of stamps but found $shown", shown >= 1)
        // The stamp view's own count for a 440dp window, reported back by the grid.
        assertTrue("stamps across was ${bridge.columnsAcross}", bridge.columnsAcross in 8..24)
    }

    @Test
    fun `the stamp layout has one header per run and no two share a key`() {
        val layout = zoomLayoutFor(assets, headers = true)
        assertTrue(layout.hasHeaders)
        assertEquals(layout.groups.size, layout.groups.map { it.key }.toSet().size)
        assertEquals(assets.size, layout.renderedAssets.size)
        assertTrue("fixture should span several months", layout.groups.size >= 3)
    }

    // ---- the pinch reaches the grid at every rung ----

    private fun pinchOutAndIn(rung: Int, horizontal: Boolean = false) {
        val zooms = mutableListOf<Float>()
        var ended = 0
        bridge.onZoom = { zooms += it }
        bridge.onZoomEnd = { ended += 1 }
        render(null, rung, horizontal)
        // Spread: the factor must come out above one, and the gesture's end must be reported.
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(center.x - 40f, center.y),
                end0 = Offset(center.x - 200f, center.y),
                start1 = Offset(center.x + 40f, center.y),
                end1 = Offset(center.x + 200f, center.y),
            )
        }
        compose.waitForIdle()
        assertTrue("a spread at rung $rung reported no zoom", zooms.isNotEmpty())
        assertTrue("a spread at rung $rung must zoom in, got ${zooms.fold(1f) { a, b -> a * b }}", zooms.fold(1f) { a, b -> a * b } > 1f)
        assertTrue("the end of the spread at rung $rung was not reported", ended >= 1)

        zooms.clear()
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(center.x - 200f, center.y),
                end0 = Offset(center.x - 40f, center.y),
                start1 = Offset(center.x + 200f, center.y),
                end1 = Offset(center.x + 40f, center.y),
            )
        }
        compose.waitForIdle()
        assertTrue("a pinch at rung $rung must zoom out, got ${zooms.fold(1f) { a, b -> a * b }}", zooms.fold(1f) { a, b -> a * b } < 1f)
    }

    @Test
    fun `a pinch is heard at one column`() = pinchOutAndIn(1)

    @Test
    fun `a pinch is heard at three columns`() = pinchOutAndIn(3)

    @Test
    fun `a pinch is heard at six columns`() = pinchOutAndIn(6)

    @Test
    fun `a pinch is heard on the stamps`() = pinchOutAndIn(ZOOM_RUNG_STAMPS)

    @Test
    fun `a pinch is heard on a sideways grid`() = pinchOutAndIn(3, horizontal = true)

    // ---- the grid on its side ----
    //
    // Read off the grid's own layout info rather than semantics: the tiles here are empty, and what
    // matters is where the lazy grid put them (TRAPS #30: render before believing header spans and
    // the scrollToItem clamp).

    private val tileKeyPrefix = "header:"

    private fun LazyGridState.tiles() =
        layoutInfo.visibleItemsInfo.filter { !it.key.toString().startsWith(tileKeyPrefix) && it.key != "footer" }

    private fun LazyGridState.headerItems() =
        layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith(tileKeyPrefix) }

    @Test
    fun `a sideways grid scrolls across and has as many rows as the rung`() {
        val state = LazyGridState()
        render("zoom-5-sideways-three-rows", 3, horizontal = true, state = state)
        assertEquals(Orientation.Horizontal, state.layoutInfo.orientation)
        val rows = state.tiles().map { it.row }.toSet()
        assertEquals(setOf(0, 1, 2), rows)
        // The first tile of each row starts at the same x: the rows line up as columns.
        assertEquals(1, state.tiles().filter { it.column == 0 }.map { it.offset.x }.toSet().size)
    }

    @Test
    fun `a sideways tile is as wide as its row allows by the tile aspect`() {
        val state = LazyGridState()
        render("zoom-6-sideways-six-rows", 6, horizontal = true, state = state)
        val tile = state.tiles().first()
        // 144 wide to 160 tall, width over height.
        assertEquals(tile.size.height * GRID_TILE_ASPECT, tile.size.width.toFloat(), 1.5f)
        // And the rows share the viewport's height less the bottom band kept for the pill.
        val usable = state.layoutInfo.viewportSize.height - 88 * compose.density.density
        assertTrue("six rows of ${tile.size.height}px must fit ${usable}px", tile.size.height * 6 <= usable)
    }

    @Test
    fun `a sideways month header is a narrow column the height of the rows`() {
        val state = LazyGridState()
        render("zoom-7-sideways-headers", 3, horizontal = true, headers = true, state = state)
        val header = state.headerItems().firstOrNull()
        assertTrue("no month header on the first screen of a sideways grid", header != null)
        val density = compose.density.density
        assertEquals(36 * density, header!!.size.width.toFloat(), 1.5f)
        // Content padding on the cross axis is not in the layout info's padding fields, so the
        // viewport less the bottom band kept for the pill is what the header must span.
        val rowsHeight = state.layoutInfo.viewportSize.height - 88 * density
        assertEquals(rowsHeight, header.size.height.toFloat(), 1.5f)
        assertTrue(compose.onAllNodes(headings).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the stamp view stays vertical whatever the axis`() {
        val state = LazyGridState()
        render("zoom-8-stamps-sideways-asked", ZOOM_RUNG_STAMPS, horizontal = true, state = state)
        assertEquals(Orientation.Vertical, state.layoutInfo.orientation)
        assertTrue(state.headerItems().isNotEmpty())
    }

    @Test
    fun `scrolling a sideways grid to its last item ends flush`() {
        val state = LazyGridState()
        render(null, 3, horizontal = true, headers = true, state = state)
        val last = assets.size + zoomLayoutFor(assets, headers = true).groups.size // photos + headers
        compose.runOnIdle { runBlocking { state.scrollToItem(last) } }
        compose.waitForIdle()
        // The clamp holds on this axis too: it cannot scroll past the end, and the footer is there.
        assertTrue("still scrollable forward after scrollToItem(last)", !state.canScrollForward)
        assertTrue(state.layoutInfo.visibleItemsInfo.any { it.key == "footer" })
    }

    @Test
    fun `scrolling a sideways grid to a header lands on that month`() {
        val state = LazyGridState()
        render(null, 3, horizontal = true, headers = true, state = state)
        val layout = zoomLayoutFor(assets, headers = true)
        val map = GridIndexMap(layout.groups.map { it.assets.size }, hasHeaders = true, trailingFooter = true)
        val target = layout.groups.size / 2
        val headerIndex = map.gridIndexOf(layout.groups.take(target).sumOf { it.assets.size }) - 1
        compose.runOnIdle { runBlocking { state.scrollToItem(headerIndex) } }
        compose.waitForIdle()
        assertEquals(headerIndex, state.firstVisibleItemIndex)
        assertEquals("header:${layout.groups[target].key}", state.layoutInfo.visibleItemsInfo.first().key)
    }

    @Test
    fun `turning the grid on its side keeps the first photo and drops the offset`() {
        val state = LazyGridState()
        val sideways = mutableStateOf(false)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Grid(rung = 3, horizontal = sideways.value, headers = false, state = state)
                    }
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(60, 37) } }
        compose.waitForIdle()
        val before = state.layoutInfo.visibleItemsInfo.first().key
        compose.runOnIdle { sideways.value = true }
        compose.waitForIdle()
        assertEquals(Orientation.Horizontal, state.layoutInfo.orientation)
        assertEquals(before, state.layoutInfo.visibleItemsInfo.first().key)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
