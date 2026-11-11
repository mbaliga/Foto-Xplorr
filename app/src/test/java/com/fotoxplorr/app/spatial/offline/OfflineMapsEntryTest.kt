package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings > Data row for Offline maps and the sheet it opens, in whichever build this runs in.
 * Composition only: Robolectric proves the row is there and opens something, not how it looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class OfflineMapsEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun dataTab() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PaneScaffold(title = "Settings", onBack = {}) {
                        SettingsTabsRoom(
                            state = emptyState(),
                            actions = noOpActions(),
                            onOpenLegacyScreen = {},
                            onOpenSupport = {},
                            onOpenMoreApps = {},
                            initialTab = SettingsTab.DATA,
                            showHeader = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the data tab has one Offline maps row with the build's own caption`() {
        dataTab()
        compose.onNodeWithText(OfflineMapsCopy.ROW_LABEL).performScrollTo().assertIsDisplayed()
        val caption = if (BuildConfig.NETWORK_FEATURES) OfflineMapsCopy.ROW_CAPTION else OfflineMapsCopy.STUB_ROW
        compose.onNodeWithText(caption).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a tap on the row opens the pane and nothing else`() {
        dataTab()
        compose.onNodeWithText(OfflineMapsCopy.ROW_LABEL).performScrollTo().performClick()
        compose.waitForIdle()
        // The row's label and the pane's title.
        assertTrue(compose.onAllNodesWithText(OfflineMapsCopy.ROW_LABEL).fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `the Offline build's pane says it needs the Connect build`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        dataTab()
        compose.onNodeWithText(OfflineMapsCopy.ROW_LABEL).performScrollTo().performClick()
        compose.waitForIdle()
        // Once as the row's caption, once as the pane's body.
        assertTrue(compose.onAllNodesWithText(OfflineMapsCopy.STUB_ROW).fetchSemanticsNodes().size >= 2)
    }
}
