package com.fotoxplorr.app.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.takahirom.roborazzi.captureRoboImage
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.gallery.ThemeMode
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.PlacesScreen
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max

/**
 * Three defects from the review of the pane work, held where a picture or the semantics tree can
 * see them: the Immersive and AI header in light theme, a disabled switch row's legibility, and
 * the size of a settings chip's touch target.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PaneReviewFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun textColorOf(node: SemanticsNodeInteraction): Color {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.color
    }

    /**
     * The node as pixels. Compose's own `captureToImage` times out under this Robolectric setup,
     * so the render path the rest of the suite uses writes the PNG and it is read back from disk.
     */
    private fun pixelsOf(node: SemanticsNodeInteraction, name: String): Bitmap {
        val path = "build/renders/$name.png"
        node.captureRoboImage(path)
        return checkNotNull(BitmapFactory.decodeFile(path)) { "could not read $path" }
    }

    /** The brightest channel of anything drawn in [node], 0..1: ink on a black ground. */
    private fun brightestInk(node: SemanticsNodeInteraction, name: String): Float {
        val image = pixelsOf(node, name)
        var best = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getPixel(x, y)
            best = max(best, max(max((argb shr 16) and 0xFF, (argb shr 8) and 0xFF), argb and 0xFF))
        }
        return best / 255f
    }

    /** Luminance of the node's top-left pixel, 0..1: the ground the text sits on. */
    private fun groundLuminance(node: SemanticsNodeInteraction, name: String): Float {
        val argb = pixelsOf(node, name).getPixel(0, 0)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    // ---- 3: the header over the pane's black ground in light theme ----

    @Test
    fun `the Immersive and AI header is pane ink on a dark ground in light theme`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState(themeMode = ThemeMode.LIGHT)) {
                PaneScaffold(title = "Discover", onBack = {}) {
                    PlacesScreen(
                        assets = emptyList(),
                        indexInput = emptyList(),
                        geoState = GeoIndexState(),
                        onIndexLocations = {},
                        onOpenAsset = { _, _ -> },
                    )
                }
            }
        }
        val title = compose.onNodeWithText("Immersive and AI")
        title.assertIsDisplayed()
        assertEquals("the title sets its own colour", RoomStyle.Ink.toArgb(), textColorOf(title).toArgb())
        val body = compose.onNodeWithText("Optional extras", substring = true)
        assertEquals(RoomStyle.InkFaint.toArgb(), textColorOf(body).toArgb())
        // And the ground behind them is the pane's own dark surface, not the theme's near-white.
        assertTrue(
            "the header ground is light: the white title would be unreadable on it",
            groundLuminance(title, "review-explore-hub-header-light") < 0.2f,
        )
    }

    // ---- 4: a disabled switch is not dimmed twice ----

    @Test
    fun `a disabled switch row keeps its caption legible`() {
        // Disabled only in the offline build; in the connect build the row is a live control.
        assumeFalse("the Wi-Fi row is live in this build", BuildConfig.NETWORK_FEATURES)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize()) {
                    PaneScaffold(title = "Settings", onBack = {}) {
                        SettingsTabsRoom(
                            state = emptyState(),
                            actions = noOpActions(),
                            onOpenLegacyScreen = {},
                            onOpenSupport = {},
                            onOpenMoreApps = {},
                            initialTab = SettingsTab.BACKGROUND,
                            showHeader = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        val caption = compose.onNodeWithText("Not available: the offline build", substring = true)
        caption.assertIsDisplayed()
        // The caption's own ink is 55% white, and 13sp strokes are thin enough that the brightest
        // pixel is about three quarters of that (measured 0.41). Under a second 50% alpha it could
        // not exceed 0.28 even at full coverage, so 0.35 separates the two.
        val brightest = brightestInk(caption, "review-disabled-switch-caption")
        assertTrue("caption is dimmed twice: brightest ink $brightest", brightest > 0.35f)
    }

    // ---- 4: chips are real touch targets ----

    @Test
    fun `a settings chip has a 48dp touch target`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaneScaffold(title = "Settings", onBack = {}) {
                    SettingsTabsRoom(
                        state = emptyState(),
                        actions = noOpActions(),
                        onOpenLegacyScreen = {},
                        onOpenSupport = {},
                        onOpenMoreApps = {},
                        initialTab = SettingsTab.APPEARANCE,
                        showHeader = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        val density = compose.density
        listOf("Privacy", "Library", "Viewer").forEach { tab ->
            val node = compose.onNodeWithText(tab, useUnmergedTree = false).fetchSemanticsNode()
            val heightDp = with(density) { node.touchBoundsInRoot.height.toDp() }
            assertTrue("$tab chip touch target is $heightDp", heightDp >= 48.dp)
        }
    }
}
