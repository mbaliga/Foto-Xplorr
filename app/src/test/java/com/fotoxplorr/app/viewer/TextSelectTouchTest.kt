package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fotoxplorr.app.textselect.FRAME_H
import com.fotoxplorr.app.textselect.FRAME_W
import com.fotoxplorr.app.textselect.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextSelectTouch]: screen distances turned into frame distances. The overlay sits inside the
 * viewer's zoom layer, so a finger of 12 dp is 12 / zoom layer pixels, and the frame is not square,
 * so the conversion must be one factor for both axes.
 */
class TextSelectTouchTest {

    // The fixture frame is 1024 x 512; a 1024 x 512 container shows it at 1:1.
    private val container = Size(FRAME_W.toFloat(), FRAME_H.toFloat())

    private fun touch(zoom: Float = 1f, density: Float = 2f, size: Size = container) =
        TextSelectTouch(page(), size, density, zoom)

    @Test
    fun `a finger is the same size in the frame on both axes of a non-square photo`() {
        // Radius is a single number: if it were measured in normalised units it would be a wide
        // ellipse on this 2:1 frame. Check the mapping is the identity scale on x and y alike.
        val t = touch()
        val a = t.frame(Offset(100f, 100f))
        val b = t.frame(Offset(100f + t.fingerRadius, 100f))
        val c = t.frame(Offset(100f, 100f + t.fingerRadius))
        assertEquals(t.fingerRadius, b.x - a.x, 0.001f)
        assertEquals(t.fingerRadius, c.y - a.y, 0.001f)
    }

    @Test
    fun `strokes and slop shrink with the zoom so a finger covers the same screen size`() {
        val atOne = touch(zoom = 1f)
        val atFour = touch(zoom = 4f)
        assertEquals(atOne.fingerRadius / 4f, atFour.fingerRadius, 0.001f)
        assertEquals(atOne.tapSlop / 4f, atFour.tapSlop, 0.001f)
        assertEquals(atOne.closeDistance / 4f, atFour.closeDistance, 0.001f)
        // 12 dp at 2 px per dp is 24 px of a frame shown at 1:1.
        assertEquals(24f, atOne.fingerRadius, 0.001f)
    }

    @Test
    fun `a photo shown smaller than its frame has a bigger finger in frame pixels`() {
        // The frame is shown at half size: 24 layer pixels cover 48 frame pixels.
        val half = touch(size = Size(FRAME_W / 2f, FRAME_H / 2f))
        assertEquals(48f, half.fingerRadius, 0.001f)
    }

    @Test
    fun `a letterboxed container maps the fit rectangle, not the container, onto the frame`() {
        // A 1024 x 1024 container letterboxes the 2:1 frame: it is 1024 x 512 and sits 256 down.
        val t = touch(size = Size(1024f, 1024f))
        val p = t.frame(Offset(100f, 256f + 100f))
        assertEquals(100f, p.x, 0.001f)
        assertEquals(100f, p.y, 0.001f)
    }

    @Test
    fun `a zoom of zero cannot blow the radius up`() {
        assertTrue(touch(zoom = 0f).fingerRadius.isFinite())
        assertEquals(touch(zoom = TextSelectTouch.MIN_ZOOM).fingerRadius, touch(zoom = 0f).fingerRadius, 0.001f)
    }

    @Test
    fun `drawing across a line selects the words the finger touched`() {
        val layout = page()
        val t = touch(zoom = 4f)
        // Along the middle of the PLATFORM / 9 line (y 115): both words, nothing from the next line.
        val drawn = t.drawn(layout, listOf(Offset(90f, 115f), Offset(200f, 115f), Offset(360f, 115f)))
        assertEquals("PLATFORM 9", drawn.text(layout))
    }

    @Test
    fun `the same stroke reaches fewer neighbours the further in the photo is zoomed`() {
        val layout = page()
        // Ends 10 px short of "only": a 24 px finger reaches it, a 6 px one does not.
        val stroke = listOf(Offset(590f, 315f), Offset(640f, 315f), Offset(690f, 315f))
        assertEquals("Exit only", touch(zoom = 1f).drawn(layout, stroke).text(layout))
        assertEquals("Exit", touch(zoom = 4f).drawn(layout, stroke).text(layout))
    }

    @Test
    fun `a loop around a line is a lasso`() {
        val layout = page()
        val t = touch(zoom = 4f)
        val loop = listOf(
            Offset(580f, 280f), Offset(740f, 280f), Offset(900f, 280f), Offset(900f, 315f), Offset(900f, 350f),
            Offset(740f, 350f), Offset(580f, 350f), Offset(580f, 315f), Offset(580f, 285f),
        )
        assertEquals("Exit only left", t.drawn(layout, loop).text(layout))
    }
}
