package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Size
import com.fotoxplorr.app.recognition.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where OCR boxes land on screen.
 *
 * Letterboxing is the whole difficulty: a 4:3 photo in a 9:19.5 phone viewport has most of the
 * screen as empty black, and a box placed as a fraction of the CONTAINER rather than of the drawn
 * IMAGE lands somewhere in that black. It looks almost right, which is the worst kind of wrong,
 * so the arithmetic is pinned here rather than eyeballed on a device.
 */
class LiveTextGeometryTest {

    @Test
    fun `a wide image in a tall container letterboxes top and bottom`() {
        // 1000x500 image, 1000x1000 container: scale 1.0, 250px of black above and below.
        val rect = fittedImageRect(Size(1000f, 1000f), imageWidth = 1000, imageHeight = 500)

        assertEquals(0f, rect.left, 0.01f)
        assertEquals(250f, rect.top, 0.01f)
        assertEquals(1000f, rect.width, 0.01f)
        assertEquals(500f, rect.height, 0.01f)
    }

    @Test
    fun `a tall image in a wide container pillarboxes left and right`() {
        val rect = fittedImageRect(Size(1000f, 1000f), imageWidth = 500, imageHeight = 1000)

        assertEquals(250f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(500f, rect.width, 0.01f)
        assertEquals(1000f, rect.height, 0.01f)
    }

    @Test
    fun `a box maps into the drawn image, not the container`() {
        // The bug this guards: a block in the middle of a letterboxed photo must sit in the
        // middle of the PHOTO, which is 250px down from the top of the container.
        val rect = fittedImageRect(Size(1000f, 1000f), imageWidth = 1000, imageHeight = 500)
        val block = TextBlock("PLATFORM 9", left = 0.25f, top = 0.4f, right = 0.75f, bottom = 0.6f)

        val box = block.toScreenRect(rect)

        assertEquals(250f, box.left, 0.01f)
        assertEquals(250f + 0.4f * 500f, box.top, 0.01f)
        assertEquals(750f, box.right, 0.01f)
        assertEquals(500f, box.width, 0.01f)
    }

    @Test
    fun `degenerate sizes do not divide by zero`() {
        // A frame can be measured at zero before layout settles; returning the container rather
        // than crashing is the only sane answer.
        val rect = fittedImageRect(Size(0f, 0f), imageWidth = 0, imageHeight = 0)
        assertTrue(rect.width == 0f && rect.height == 0f)

        val stillFine = fittedImageRect(Size(100f, 100f), imageWidth = 0, imageHeight = 10)
        assertEquals(100f, stillFine.width, 0.01f)
    }

    @Test
    fun `the overlay frame for an EXIF rotated portrait is the upright size, not the stored one`() {
        // MediaStore reports the sensor grid (4000x3000 landscape); the file displays upright
        // (3000x4000 portrait) and the OCR boxes are normalised to that upright bitmap (TRAPS 48).
        val frame = PlaceMorph.restingImageSize(storedWidth = 4000, storedHeight = 3000, displayWidth = 3000, displayHeight = 4000)
        assertEquals(3000, frame.width.toInt())
        assertEquals(4000, frame.height.toInt())

        val container = Size(1000f, 1000f)
        val upright = fittedImageRect(container, frame.width.toInt(), frame.height.toInt())
        val stored = fittedImageRect(container, 4000, 3000)

        // The photo is pillarboxed; fitting the stored size letterboxes it instead and lands
        // every box somewhere else.
        assertEquals(125f, upright.left, 0.01f)
        assertEquals(0f, upright.top, 0.01f)
        assertEquals(750f, upright.width, 0.01f)
        assertEquals(0f, stored.left, 0.01f)
        assertEquals(125f, stored.top, 0.01f)

        val block = TextBlock("PLATFORM 9", left = 0.5f, top = 0.5f, right = 0.6f, bottom = 0.6f)
        assertEquals(125f + 0.5f * 750f, block.toScreenRect(upright).left, 0.01f)
        assertEquals(500f, block.toScreenRect(upright).top, 0.01f)
    }

    @Test
    fun `with no EXIF size read the stored size is the frame`() {
        val frame = PlaceMorph.restingImageSize(storedWidth = 4000, storedHeight = 3000, displayWidth = 0, displayHeight = 0)
        assertEquals(4000, frame.width.toInt())
        assertEquals(3000, frame.height.toInt())
    }
}
