package com.fotoxplorr.app.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tab strip (glyph + word, selection stated beyond colour) and the Services doorway. Composition only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SettingsServicesTabTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settings(tab: SettingsTab = SettingsTab.APPEARANCE) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PaneScaffold(title = "Settings", onBack = {}) {
                        SettingsTabsRoom(
                            state = emptyState(), actions = noOpActions(), onOpenLegacyScreen = {},
                            onOpenSupport = {}, onOpenMoreApps = {}, initialTab = tab, showHeader = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    private val isSelectedTab = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Test
    fun `every tab is a Tab with its word, a glyph, and exactly the open one is selected`() {
        settings(SettingsTab.PRIVACY)
        SettingsTab.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        compose.onNodeWithText("Privacy").assert(isSelectedTab)
        compose.onNodeWithText("Appearance").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        // One glyph per tab, drawn beside the word and so not named twice.
        assertTrue(compose.onAllNodes(hasContentDescription("Privacy")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the Services tab lists AI keys, Models and Sync, Sync opens the hub and Back returns to the tabs`() {
        settings()
        compose.onNodeWithText("Services").performClick()
        compose.onNodeWithText(SettingsRow.AI_KEYS.label).assertIsDisplayed()
        compose.onNodeWithText(SettingsRow.MODELS.label).assertIsDisplayed()
        compose.onNodeWithText(SettingsRow.SYNC.label).performScrollTo().performClick()
        compose.waitForIdle()
        // The hub replaced the tabs.
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Appearance")).fetchSemanticsNodes().isEmpty())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Services").assert(isSelectedTab)
    }
}
