package com.fotoxplorr.app.palette

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tappable palette bar: a tap on a segment reports that segment's own colour, a sliver is
 * still a segment, and an empty palette is nothing at all rather than a crash.
 *
 * `NATIVE` graphics mode and `sdk = 34`, same as [com.fotoxplorr.app.gallery.CustomThemeJsonDialogTest]:
 * legacy Robolectric graphics draws nothing, and the strip here is a real draw call.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaletteSwatchRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val teal = 0xFF1ABCF0.toInt()

    @Test
    fun `tapping a segment reports that segment's ARGB, not its neighbour's`() {
        var tapped: Int? = null
        compose.setContent {
            PaletteSwatchRow(
                swatches = listOf(PaletteSwatch(red, 0.7f), PaletteSwatch(blue, 0.3f)),
                onSwatchTap = { tapped = it },
            )
        }

        compose.onNodeWithContentDescription("Search photos coloured #0000FF").performClick()
        assertEquals(blue, tapped)

        compose.onNodeWithContentDescription("Search photos coloured #FF0000").performClick()
        assertEquals(red, tapped)
    }

    @Test
    fun `a sliver of colour is still its own tappable segment`() {
        var tapped: Int? = null
        compose.setContent {
            PaletteSwatchRow(
                // 1% of the photo: drawn at its true width it would be a hairline nobody could hit.
                swatches = listOf(PaletteSwatch(red, 0.99f), PaletteSwatch(teal, 0.01f)),
                onSwatchTap = { tapped = it },
            )
        }

        compose.onNodeWithContentDescription("Search photos coloured #1ABCF0").performClick()
        assertEquals(teal, tapped)
    }

    @Test
    fun `the hex values are printed under the strip`() {
        compose.setContent {
            PaletteSwatchRow(
                swatches = listOf(PaletteSwatch(red, 0.7f), PaletteSwatch(teal, 0.3f)),
                onSwatchTap = {},
            )
        }

        compose.onNodeWithText("#FF0000", substring = true).assertExists()
        compose.onNodeWithText("#1ABCF0", substring = true).assertExists()
    }

    @Test
    fun `an empty palette renders nothing and does not crash`() {
        var tapped: Int? = null
        compose.setContent {
            PaletteSwatchRow(swatches = emptyList(), onSwatchTap = { tapped = it })
        }

        compose.onAllNodesWithContentDescription("Search photos coloured", substring = true).assertCountEquals(0)
        assertNull(tapped)
    }
}
