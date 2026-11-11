package com.fotoxplorr.app.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySort
import com.fotoxplorr.app.gallery.GridChromeBridge
import com.fotoxplorr.app.gallery.LayoutAnchor
import com.fotoxplorr.app.gallery.LocalGridChromeBridge
import com.fotoxplorr.app.gallery.SyntheticCatalogue
import com.fotoxplorr.app.gallery.TimelineGrouping
import com.fotoxplorr.app.gallery.ZoomGrid
import com.fotoxplorr.app.gallery.sortAssets
import com.fotoxplorr.app.gallery.zoomLayoutFor
import com.fotoxplorr.app.ui.FotoXplorrTheme
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
 * The grid leaves the screen (Settings, a destination screen and the map replace it in the browsing
 * `when`) while the browser's scroll state and [LayoutAnchor] stay: when the layout changes under
 * the scroll position meanwhile, the grid that comes back must still land on the photo the top line
 * stood over, not on the same numeric item hundreds of photos later.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ZoomGridAnchorTest {

    @get:Rule
    val compose = createComposeRule()

    private val assets = sortAssets(SyntheticCatalogue.assets(1_500), GallerySort.NEWEST)
    private val bridge = GridChromeBridge()

    // What the browser owns: both outlive the grid.
    private val state = LazyGridState()
    private val anchor = LayoutAnchor()

    private var present by mutableStateOf(true)
    private var headers by mutableStateOf(true)

    private fun mount() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalGridChromeBridge provides bridge) {
                    Box(Modifier.fillMaxSize()) {
                        if (present) {
                            val layout = remember(headers) {
                                zoomLayoutFor(assets, headers = headers, grouping = TimelineGrouping.DAY)
                            }
                            ZoomGrid(
                                layout = layout,
                                rung = COLUMNS,
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
                            )
                        }
                    }
                }
            }
        }
    }

    /** A day header well down the list, and the item its photos start at without headers. */
    private fun dayHeaderDownTheList(): Pair<Int, Int> {
        val groups = zoomLayoutFor(assets, headers = true, grouping = TimelineGrouping.DAY).groups
        assertTrue("fixture should span many days, got ${groups.size}", groups.size > 30)
        val target = 20
        assertTrue("the day under the header needs four photos", groups[target].assets.size >= 4)
        val header = groups.take(target).sumOf { it.assets.size + 1 }
        val firstPhoto = groups.take(target).sumOf { it.assets.size }
        return header to firstPhoto
    }

    /** The grid reports the first item of the top ROW: three columns here, so a multiple of three. */
    private fun rowStart(index: Int) = index - index % COLUMNS

    private fun scrollTo(index: Int) {
        compose.runOnIdle { runBlocking { state.scrollToItem(index) } }
        compose.waitForIdle()
        assertEquals(index, compose.runOnIdle { state.firstVisibleItemIndex })
    }

    @Test
    fun `headers switched off while the grid was away land on the photo under the old header`() {
        val (header, firstPhoto) = dayHeaderDownTheList()
        assertTrue(header > firstPhoto)
        mount()
        scrollTo(header)

        // Settings opens (the grid is disposed), Timeline goes off, Settings closes.
        present = false
        compose.waitForIdle()
        headers = false
        compose.waitForIdle()
        present = true
        compose.waitForIdle()

        // The row that holds the photo, not the item `header` numerically, which is a later photo.
        assertEquals(rowStart(firstPhoto), compose.runOnIdle { state.firstVisibleItemIndex })
    }

    @Test
    fun `the layout left unchanged while the grid was away keeps the scroll position`() {
        val (header, _) = dayHeaderDownTheList()
        mount()
        scrollTo(header)

        present = false
        compose.waitForIdle()
        present = true
        compose.waitForIdle()

        assertEquals(header, compose.runOnIdle { state.firstVisibleItemIndex })
    }

    @Test
    fun `a photo at the top is kept by its key when the headers go while the grid was away`() {
        val (header, firstPhoto) = dayHeaderDownTheList()
        mount()
        // The second row of the day, whose first photo (the day's fourth) has a key without headers too.
        scrollTo(header + 1 + COLUMNS)

        present = false
        compose.waitForIdle()
        headers = false
        compose.waitForIdle()
        present = true
        compose.waitForIdle()

        assertEquals(rowStart(firstPhoto + COLUMNS), compose.runOnIdle { state.firstVisibleItemIndex })
    }

    private companion object {
        const val COLUMNS = 3
    }
}
