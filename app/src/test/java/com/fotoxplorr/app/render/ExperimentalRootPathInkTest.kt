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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.experimental.ExperimentalScreen
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ThemeMode
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.HelpCopy
import com.fotoxplorr.app.ui.RoomStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Experimental hub on the root-destination path, where it is NOT under a PaneScaffold and
 * so nothing above it provides a content colour. In light theme the theme's own text colour is dark;
 * the hub sits on the pane's black ground, so ExperimentalScreen provides the pane's ink once and every
 * screen it opens, which names no colour of its own, has to come out light too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall enough that the lazy grid composes its last item, the privacy paragraph.
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class ExperimentalRootPathInkTest {

    @get:Rule
    val compose = createComposeRule()

    private fun textColorOf(node: SemanticsNodeInteraction): Color {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.color
    }

    private fun showExperimentalOnBlack() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState(themeMode = ThemeMode.LIGHT)) {
                // The root-destination path: the pane's black ground and no PaneScaffold.
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ExperimentalScreen(
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
    }

    @Test
    fun `the privacy boundaries copy is pane ink on the root path in light theme`() {
        showExperimentalOnBlack()
        assertEquals(
            RoomStyle.Ink.toArgb(),
            textColorOf(compose.onNodeWithText("Privacy boundaries")).toArgb(),
        )
        assertEquals(
            RoomStyle.InkFaint.toArgb(),
            textColorOf(compose.onNodeWithText(HelpCopy.experimentalPrivacy(BuildConfig.NETWORK_FEATURES))).toArgb(),
        )
        assertEquals(
            RoomStyle.InkFaint.toArgb(),
            textColorOf(compose.onNodeWithText(HelpCopy.Note.EXPERIMENTAL_LOCATION)).toArgb(),
        )
    }

    @Test
    fun `the hub header is pane ink on the root path in light theme`() {
        showExperimentalOnBlack()
        assertEquals(
            RoomStyle.Ink.toArgb(),
            textColorOf(compose.onNodeWithText("Experimental")).toArgb(),
        )
    }

    @Test
    fun `the similarity explorer opened from the hub is pane ink in light theme`() {
        showExperimentalOnBlack()
        compose.onNodeWithText("Organise by similarity", substring = true).performClick()
        compose.waitForIdle()
        // The explorer names no colour for its title or its line under it.
        assertEquals(
            RoomStyle.Ink.toArgb(),
            textColorOf(compose.onNodeWithText("Organise by similarity")).toArgb(),
        )
        assertEquals(
            RoomStyle.Ink.toArgb(),
            textColorOf(compose.onNodeWithText("Groups of look-alike photos")).toArgb(),
        )
    }
}
