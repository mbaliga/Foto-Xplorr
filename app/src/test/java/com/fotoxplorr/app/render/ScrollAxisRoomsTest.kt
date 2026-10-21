package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GalleryViewOptionsRoom
import com.fotoxplorr.app.gallery.ScrollAxis
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two places the photo scroll direction is chosen -- the view options room and Settings >
 * Library -- and the words the date orders take once the grid scrolls across.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ScrollAxisRoomsTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<ScrollAxis>()

    private fun actions(): GalleryActions = noOpActions().copy(onSetScrollAxis = { chosen += it })

    private fun stateWith(preferences: GalleryPreferencesState) = emptyState().copy(preferences = preferences)

    private fun room(preferences: GalleryPreferencesState) {
        compose.setContent {
            FotoXplorrTheme(preferences) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    GalleryViewOptionsRoom(state = stateWith(preferences), actions = actions())
                }
            }
        }
    }

    @Composable
    private fun Library(preferences: GalleryPreferencesState) {
        PaneScaffold(title = "Settings", onBack = {}) {
            SettingsTabsRoom(
                state = stateWith(preferences),
                actions = actions(),
                onOpenLegacyScreen = {},
                onOpenSupport = {},
                onOpenMoreApps = {},
                initialTab = SettingsTab.LIBRARY,
                showHeader = false,
                modifier = Modifier.weight(1f),
            )
        }
    }

    private fun settings(preferences: GalleryPreferencesState) {
        compose.setContent {
            FotoXplorrTheme(preferences) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { Library(preferences) }
            }
        }
    }

    @Test
    fun `the view options room names the direction and a tap flips it`() {
        room(GalleryPreferencesState())
        compose.onNodeWithText("Scroll direction").assertIsDisplayed()
        compose.onNodeWithText("Vertical").assertIsDisplayed()
        compose.onNodeWithText("Scroll direction").performClick()
        assertEquals(listOf(ScrollAxis.HORIZONTAL), chosen)
    }

    @Test
    fun `a sideways choice flips back to vertical`() {
        room(GalleryPreferencesState(scrollAxis = ScrollAxis.HORIZONTAL))
        compose.onNodeWithText("Horizontal").assertIsDisplayed()
        compose.onNodeWithText("Scroll direction").performClick()
        assertEquals(listOf(ScrollAxis.VERTICAL), chosen)
    }

    @Test
    fun `the date orders read top and bottom on a vertical grid`() {
        room(GalleryPreferencesState())
        compose.onNodeWithText("Latest at top").assertIsDisplayed()
        compose.onNodeWithText("Latest at bottom").assertIsDisplayed()
    }

    @Test
    fun `the date orders read left and right on a sideways grid`() {
        room(GalleryPreferencesState(scrollAxis = ScrollAxis.HORIZONTAL))
        compose.onNodeWithText("Latest at left").assertIsDisplayed()
        compose.onNodeWithText("Latest at right").assertIsDisplayed()
    }

    @Test
    fun `at the stamps the grid is vertical so the orders stay top and bottom`() {
        room(GalleryPreferencesState(scrollAxis = ScrollAxis.HORIZONTAL, gridZoomRung = 7))
        compose.onNodeWithText("Latest at top").assertIsDisplayed()
    }

    @Test
    fun `settings offer the direction as a choice`() {
        settings(GalleryPreferencesState())
        compose.onNodeWithText("Scroll direction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Horizontal").performScrollTo().performClick()
        assertEquals(listOf(ScrollAxis.HORIZONTAL), chosen)
    }
}
