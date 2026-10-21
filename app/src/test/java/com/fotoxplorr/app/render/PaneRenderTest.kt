package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GridLongPress
import com.fotoxplorr.app.gallery.LegacyScreen
import com.fotoxplorr.app.gallery.LegacyScreenHost
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.gallery.ThemeMode
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings pane and the destination panes, hosted the way the browser hosts them: inside
 * [PaneScaffold], on the real theme. Same configuration as [NewSurfaceRenderTest].
 *
 * Renders land in build/renders and are compared by eye; the assertions here are the things a
 * picture cannot say -- every tab is on screen at once, a switch row is ONE semantics node, the
 * chosen tab survives a save and restore, and a pane in light theme still reads white on black.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PaneRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(name: String, preferences: GalleryPreferencesState = GalleryPreferencesState(), content: @Composable () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(preferences) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Composable
    private fun SettingsPane(tab: SettingsTab, actions: GalleryActions = noOpActions()) {
        PaneScaffold(title = "Settings", onBack = {}) {
            SettingsTabsRoom(
                state = emptyState(),
                actions = actions,
                onOpenLegacyScreen = {},
                onOpenSupport = {},
                onOpenMoreApps = {},
                initialTab = tab,
                showHeader = false,
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    private fun DestinationPane(screen: LegacyScreen) {
        LegacyScreenHost(
            screen = screen,
            state = emptyState(),
            actions = noOpActions(),
            query = "",
            onBack = {},
            onOpenRoute = {},
            onRequestUnlock = { _, _ -> },
            onOpenSettings = {},
        )
    }

    /** Appearance is the tab the pane opens on; all nine tabs must be reachable without scrolling. */
    @Test
    fun `settings pane shows all nine tabs at once`() {
        render("settings-3-appearance") { SettingsPane(SettingsTab.APPEARANCE) }
        SettingsTab.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        assertEquals(9, SettingsTab.entries.size)
    }

    @Test
    fun `settings pane library tab`() {
        render("settings-4-pane-library") { SettingsPane(SettingsTab.LIBRARY) }
        compose.onNodeWithText("Tidy up").assertIsDisplayed()
    }

    @Test
    fun `albums pane`() {
        render("pane-albums") { DestinationPane(LegacyScreen.ALBUMS) }
        compose.onNodeWithText("Albums").assertIsDisplayed()
    }

    @Test
    fun `discover pane`() {
        render("pane-discover") { DestinationPane(LegacyScreen.DISCOVER) }
        compose.onNodeWithText("Discover").assertIsDisplayed()
    }

    @Test
    fun `library pane`() {
        render("pane-library") { DestinationPane(LegacyScreen.LIBRARY) }
        compose.onNodeWithText("Library").assertIsDisplayed()
    }

    /** The one light-theme capture: nothing on the pane may go dark-on-black. */
    @Test
    fun `library pane in light theme`() {
        render("pane-library-light-theme", GalleryPreferencesState(themeMode = ThemeMode.LIGHT)) {
            DestinationPane(LegacyScreen.LIBRARY)
        }
        compose.onNodeWithText("Library").assertIsDisplayed()
    }

    /**
     * Text that sets no colour of its own takes [LocalContentColor]. In light theme that is the
     * theme's dark `onSurface`, on a ground that is literally black -- which is what made the
     * destination screens unreadable. The scaffold has to provide white.
     */
    @Test
    fun `a pane provides white content colour even in light theme`() {
        var seen: Color = Color.Unspecified
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState(themeMode = ThemeMode.LIGHT)) {
                PaneScaffold(title = "Albums", onBack = {}) {
                    seen = LocalContentColor.current
                    Text("A heading that sets no colour")
                }
            }
        }
        compose.waitForIdle()
        assertEquals(RoomStyle.Ink, seen)
    }

    @Test
    fun `the back chevron calls onBack`() {
        var backs = 0
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaneScaffold(title = "Settings", onBack = { backs++ }) { Text("body") }
            }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    /**
     * A switch row is one thing to a screen reader. It used to be a clickable row around a toggle
     * that was itself clickable, so every setting announced twice.
     */
    @Test
    fun `each switch row is exactly one switch node and the row toggles it`() {
        var fit: Boolean? = null
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                SettingsPane(SettingsTab.MEDIA, noOpActions().copy(onSetFitToTile = { fit = it }))
            }
        }
        val switches = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        // Fill the tile and Play animations. (Long press is a choice row now, not a switch.)
        switches.assertCountEquals(2)

        compose.onNodeWithText("Fill the tile").performClick()
        // The default is fit-to-tile ON, so a tap on the row turns it off.
        assertEquals(!GalleryPreferencesState().fitToTile, fit)
    }

    /** Owner #3 / #14: what a hold does is a setting, one chip per [GridLongPress]. */
    @Test
    fun `the long press row offers every action and reports the one tapped`() {
        var chosen: GridLongPress? = null
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                SettingsPane(SettingsTab.MEDIA, noOpActions().copy(onSetGridLongPress = { chosen = it }))
            }
        }
        compose.onNodeWithText("Long press does").assertExists()
        GridLongPress.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onNodeWithText("Nothing").performClick()
        assertEquals(GridLongPress.NOTHING, chosen)
    }

    /** Leaving Settings for a destination screen and coming back must land on the same tab. */
    @Test
    fun `the chosen tab survives a save and restore`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) { SettingsPane(SettingsTab.APPEARANCE) }
        }
        compose.onNodeWithText("Privacy").performClick()
        compose.onNodeWithText("Blur sensitive photos").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Blur sensitive photos").assertIsDisplayed()
        // "Accent" is Appearance's own row: if the tab had reset, it would be back.
        compose.onNodeWithText("Accent").assertDoesNotExist()
    }
}
