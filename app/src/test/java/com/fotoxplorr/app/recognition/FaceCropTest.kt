package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCropTest {

    private fun box(l: Float, t: Float, r: Float, b: Float, aspect: Float = 1f) = FaceBox(l, t, r, b, aspect)

    private fun NormalisedRect.pixelSide(aspect: Float): Pair<Float, Float> = (width * aspect) to height

    @Test
    fun `a centred face gets a square with the margin on every side`() {
        val crop = FaceCrop.squareAround(box(0.4f, 0.4f, 0.6f, 0.6f), margin = 0.5f)!!
        // 0.2 face side, margin 0.5 each side => 0.4 square, centred.
        assertEquals(0.3f, crop.left, 1e-5f)
        assertEquals(0.7f, crop.right, 1e-5f)
        assertEquals(0.3f, crop.top, 1e-5f)
        assertEquals(0.7f, crop.bottom, 1e-5f)
    }

    @Test
    fun `the crop is square in pixels on a wide frame`() {
        val aspect = 1.5f
        val crop = FaceCrop.squareAround(box(0.4f, 0.3f, 0.5f, 0.5f, aspect), margin = 0.25f)!!
        val (w, h) = crop.pixelSide(aspect)
        assertEquals(w, h, 1e-4f)
    }

    @Test
    fun `a face in the corner is slid back inside the photo and keeps its full size`() {
        val crop = FaceCrop.squareAround(box(0.0f, 0.0f, 0.2f, 0.2f), margin = 0.5f)!!
        assertEquals(0f, crop.left, 1e-6f)
        assertEquals(0f, crop.top, 1e-6f)
        assertEquals(0.4f, crop.width, 1e-5f)
        assertEquals(0.4f, crop.height, 1e-5f)
    }

    @Test
    fun `a face on the far edge is slid back from the right and bottom`() {
        val crop = FaceCrop.squareAround(box(0.8f, 0.8f, 1.0f, 1.0f), margin = 0.5f)!!
        assertEquals(1f, crop.right, 1e-5f)
        assertEquals(1f, crop.bottom, 1e-5f)
        assertEquals(0.4f, crop.width, 1e-5f)
    }

    @Test
    fun `a face bigger than the frame once margined shrinks to the frame's short side`() {
        val aspect = 0.5f // tall, narrow frame
        val crop = FaceCrop.squareAround(box(0.05f, 0.3f, 0.95f, 0.7f, aspect), margin = 0.45f)!!
        assertEquals(0f, crop.left, 1e-5f)
        assertEquals(1f, crop.right, 1e-5f)
        val (w, h) = crop.pixelSide(aspect)
        assertEquals(w, h, 1e-4f)
        assertTrue(crop.top >= 0f && crop.bottom <= 1f)
    }

    @Test
    fun `an unusable box has no crop`() {
        assertNull(FaceCrop.squareAround(box(0.5f, 0.5f, 0.4f, 0.6f)))
        assertNull(FaceCrop.squareAround(box(0.1f, 0.1f, 0.2f, 0.2f, aspect = 0f)))
        assertNull(FaceCrop.squareAround(box(-0.1f, 0.1f, 0.2f, 0.2f)))
    }

    @Test
    fun `pixels are at least one and inside the bitmap`() {
        val rect = NormalisedRect(0.999f, 0.999f, 1f, 1f)
        val px = rect.toPixels(100, 80)
        assertTrue(px[0] in 0..99 && px[1] in 0..79)
        assertTrue(px[2] >= 1 && px[3] >= 1)
        assertTrue(px[0] + px[2] <= 100 && px[1] + px[3] <= 80)
        assertNotNull(px)
    }
}
