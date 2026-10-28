package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.WheelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The longest destination word has to be readable in the narrowest places the rail is drawn: the
 * 260 dp pinned rail and the pull-out room on a 360 dp phone. At 44 sp bold "Experimental" is
 * about 274 sp wide, wider than either leaves for it, so the row shrinks a word that would
 * otherwise be cut to "Experim...". Robolectric draws with whatever face it resolves, so these
 * prove the fitting rule, not the pixels; the device check is a look at the real rail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DestinationWordRailFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = HyleDestination.entries.map { WheelItem(it.name, it.label) }

    private fun mountAt(widthDp: Int) {
        compose.setContent {
            DestinationRailPanel(
                items = items,
                selectedId = HyleDestination.PLACES.name,
                onSelect = {},
                onReselect = {},
                onOpenSettings = {},
                modifier = Modifier.width(widthDp.dp).fillMaxHeight(),
            )
        }
    }

    private fun layoutOf(label: String): TextLayoutResult {
        val node = compose.onNodeWithText(label).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test
    fun `Experimental is not cut on the 260 dp pinned rail`() {
        mountAt(260)
        assertFalse("the word is cut", layoutOf(HyleDestination.PLACES.label).isLineEllipsized(0))
    }

    @Test
    fun `Experimental is not cut in the pull-out room of a 360 dp phone`() {
        // The shell pads the room by its 72 dp band, so the panel gets 288 dp.
        mountAt(288)
        assertFalse(layoutOf(HyleDestination.PLACES.label).isLineEllipsized(0))
    }

    @Test
    fun `a word that already fits keeps the full size`() {
        mountAt(260)
        assertFalse(layoutOf("Photos").isLineEllipsized(0))
        assertEquals(
            "Photos is drawn at the reference size",
            RAIL_FONT_SP.toFloat(),
            fontSizeOf("Photos"),
            0.01f,
        )
    }

    private fun fontSizeOf(label: String): Float =
        layoutOf(label).layoutInput.style.fontSize.value

    @Test
    fun `a shrunken word is never smaller than the floor`() {
        mountAt(120)
        val size = fontSizeOf(HyleDestination.PLACES.label)
        assertTrue("$size below the floor", size >= RAIL_MIN_FONT_SP - 0.01f)
    }

    @Test
    fun `the fitting rule scales to the room and stops at the floor`() {
        assertEquals(44f, railFontSpFor(naturalPx = 200f, availablePx = 300f), 0.001f)
        assertEquals(44f, railFontSpFor(naturalPx = 300f, availablePx = 300f), 0.001f)
        // A quarter too wide: three quarters of the size, less the small safety margin.
        assertEquals(44f * 0.75f * 0.98f, railFontSpFor(naturalPx = 400f, availablePx = 300f), 0.001f)
        assertEquals(RAIL_MIN_FONT_SP.toFloat(), railFontSpFor(naturalPx = 6000f, availablePx = 100f), 0.001f)
        assertEquals(44f, railFontSpFor(naturalPx = 0f, availablePx = 0f), 0.001f)
    }
}
