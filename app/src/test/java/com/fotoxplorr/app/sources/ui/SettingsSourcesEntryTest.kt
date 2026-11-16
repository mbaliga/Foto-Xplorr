package com.fotoxplorr.app.sources.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SettingsIcon
import com.fotoxplorr.app.gallery.SettingsRow
import com.fotoxplorr.app.gallery.SettingsSection
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Settings entry: one iconed row in the Library tab opens the Sources pane, and a phone with no sources gets no database. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SettingsSourcesEntryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() = SourcesRuntime.forgetInstance()
    @After fun tearDown() = SourcesRuntime.forgetInstance()

    @Test
    fun `the row and the section have a glyph and say what they are`() {
        assertEquals(SettingsIcon.SD_STORAGE, SettingsRow.SOURCES.icon)
        assertEquals(SettingsIcon.SD_STORAGE, SettingsSection.SOURCES.icon)
        assertEquals("Folders and drives", SettingsRow.SOURCES.label)
        assertEquals(SourcesCopy.TITLE, SettingsRow.SOURCES.label)
    }

    @Test
    fun `the Library tab offers Folders and drives and it opens the pane with the empty state and no database file`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PaneScaffold(title = "Settings", onBack = {}) {
                        SettingsTabsRoom(
                            state = emptyState(), actions = noOpActions(), onOpenLegacyScreen = {},
                            onOpenSupport = {}, onOpenMoreApps = {}, initialTab = SettingsTab.LIBRARY, showHeader = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("SOURCES").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SettingsRow.SOURCES.label).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SourcesCopy.EMPTY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(SourcesCopy.ADD).assertIsDisplayed()
        assertFalse("opening the screen must not create the database", context.getDatabasePath("fotoz-sources.db").exists())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithText(SourcesCopy.EMPTY_TITLE).assertCountEquals(0)
        compose.onNodeWithText("SOURCES").performScrollTo().assertIsDisplayed()
    }
}
