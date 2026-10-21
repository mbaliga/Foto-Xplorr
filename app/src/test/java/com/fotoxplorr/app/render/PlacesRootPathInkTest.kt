package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ThemeMode
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.PlacesScreen
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.RoomStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Immersive and AI hub on the root-destination path, where it is NOT under a PaneScaffold and
 * so nothing above it provides a content colour. In light theme the theme's own text colour is dark;
 * the hub sits on the pane's black ground, so its Privacy boundaries copy has to set its own ink.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall enough that the lazy grid composes its last item, the privacy paragraph.
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class PlacesRootPathInkTest {

    @get:Rule
    val compose = createComposeRule()

    private fun textColorOf(node: SemanticsNodeInteraction): Color {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.color
    }

    @Test
    fun `the privacy boundaries copy is pane ink on the root path in light theme`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState(themeMode = ThemeMode.LIGHT)) {
                // The root-destination path: the pane's black ground and no PaneScaffold.
                Box(Modifier.fillMaxSize().background(Color.Black)) {
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
        compose.waitForIdle()
        assertEquals(
            RoomStyle.Ink.toArgb(),
            textColorOf(compose.onNodeWithText("Privacy boundaries")).toArgb(),
        )
        assertEquals(
            RoomStyle.InkFaint.toArgb(),
            textColorOf(compose.onNodeWithText("Similarity indexing is local", substring = true)).toArgb(),
        )
    }
}
