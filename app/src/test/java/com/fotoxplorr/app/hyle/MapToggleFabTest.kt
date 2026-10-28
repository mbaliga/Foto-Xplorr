package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bottom-left disc and the pill it sits beside: what a screen reader hears, how big a thumb's
 * target is, and that the three controls along the bottom edge do not overlap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class MapToggleFabTest {

    @get:Rule
    val compose = createComposeRule()

    private var showingMap by mutableStateOf(false)
    private var clicks = 0

    private fun mountDisc() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    MapToggleFab(showingMap = showingMap, onClick = { clicks++ })
                }
            }
        }
    }

    @Test
    fun `on the grid the disc offers the map`() {
        showingMap = false
        mountDisc()
        compose.onNodeWithContentDescription("Show map").assertHasClickAction()
        compose.onNodeWithContentDescription("Show grid").assertDoesNotExist()
    }

    @Test
    fun `on the map the disc offers the grid`() {
        showingMap = true
        mountDisc()
        compose.onNodeWithContentDescription("Show grid").assertHasClickAction()
        compose.onNodeWithContentDescription("Show map").assertDoesNotExist()
    }

    @Test
    fun `the description follows the state without recreating the disc`() {
        showingMap = false
        mountDisc()
        compose.onNodeWithContentDescription("Show map").assertExists()
        compose.runOnIdle { showingMap = true }
        compose.onNodeWithContentDescription("Show grid").assertExists()
    }

    @Test
    fun `the disc is a button at least 48dp across`() {
        mountDisc()
        compose.onNodeWithContentDescription("Show map")
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun `a tap calls back exactly once`() {
        clicks = 0
        mountDisc()
        compose.onNodeWithContentDescription("Show map").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `the pill leaves room for both discs on a phone`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FloatingPillControl(
                        caption = "412 located",
                        onToggleDensity = {},
                        onOpenTimeRange = {},
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                    )
                    SearchFab(
                        onClick = {},
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = SEARCH_FAB_MARGIN_END.dp, bottom = SEARCH_FAB_MARGIN_BOTTOM.dp),
                    )
                    MapToggleFab(
                        showingMap = false,
                        onClick = {},
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(start = SEARCH_FAB_MARGIN_END.dp, bottom = SEARCH_FAB_MARGIN_BOTTOM.dp),
                    )
                }
            }
        }
        val pill = compose.onNodeWithContentDescription("Filter by time").fetchSemanticsNode().boundsInRoot
        val density = compose.onNodeWithContentDescription("Change grid density").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithContentDescription("Show map").fetchSemanticsNode().boundsInRoot
        // The pill's buttons sit inside the pill; neither disc may touch the buttons or the pill's
        // own caption span, which the buttons bracket on the right.
        val pillSpan = Rect(pill.left, pill.top, density.right, density.bottom)
        assertFalse("map disc overlaps the pill", map.overlaps(pillSpan))
        assertFalse("search disc overlaps the pill", search.overlaps(pillSpan))
        assertFalse("the discs overlap each other", map.overlaps(search))
    }

    @Test
    fun `the pill leaves out a button whose callback is null`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FloatingPillControl(
                        caption = "",
                        onToggleDensity = null,
                        onOpenTimeRange = null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Change grid density").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter by time").assertDoesNotExist()
    }

    @Test
    fun `the pill's time button calls back and the density button still does`() {
        var time = 0
        var density = 0
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FloatingPillControl(
                        caption = "",
                        onToggleDensity = { density++ },
                        onOpenTimeRange = { time++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Filter by time").performClick()
        compose.onNodeWithContentDescription("Change grid density").performClick()
        assertEquals(1, time)
        assertEquals(1, density)
    }
}
