package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySort
import com.fotoxplorr.app.gallery.GridChromeBridge
import com.fotoxplorr.app.gallery.LocalGridChromeBridge
import com.fotoxplorr.app.gallery.SyntheticCatalogue
import com.fotoxplorr.app.gallery.ZoomGrid
import com.fotoxplorr.app.gallery.sortAssets
import com.fotoxplorr.app.gallery.stampHeadersShown
import com.fotoxplorr.app.gallery.zoomLayoutFor
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
    private fun Grid(rung: Int) {
        val layout = zoomLayoutFor(assets, headers = stampHeadersShown(rung, GallerySort.NEWEST, masonry = false))
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
            gridState = rememberLazyGridState(),
            fitToTile = true,
            loopAnimations = false,
            animatedIds = emptySet(),
        )
    }

    private fun render(name: String?, rung: Int) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { Grid(rung) }
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

    private fun pinchOutAndIn(rung: Int) {
        val zooms = mutableListOf<Float>()
        var ended = 0
        bridge.onZoom = { zooms += it }
        bridge.onZoomEnd = { ended += 1 }
        render(null, rung)
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
}
