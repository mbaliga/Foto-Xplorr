package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropInteractionTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f) {
        assertTrue("expected $expected, was $actual", abs(expected - actual) < tolerance)
    }

    @Test
    fun `MOVE translates the whole box without changing its size`() {
        val crop = CropRect(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = crop.dragged(CropHandle.MOVE, dxNorm = 0.1f, dyNorm = 0.05f)
        assertClose(0.3f, moved.left)
        assertClose(0.25f, moved.top)
        assertClose(0.4f, moved.width)
        assertClose(0.3f, moved.height)
    }

    @Test
    fun `MOVE is clamped so the box cannot leave the image`() {
        val crop = CropRect(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = crop.dragged(CropHandle.MOVE, dxNorm = 5f, dyNorm = -5f)
        assertClose(1f - moved.width, moved.left)
        assertClose(0f, moved.top)
    }

    @Test
    fun `a corner handle drags only its own two edges when free`() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = crop.dragged(CropHandle.TOP_LEFT, dxNorm = 0.1f, dyNorm = -0.05f)
        assertClose(0.3f, dragged.left)
        assertClose(0.15f, dragged.top)
        assertClose(0.8f, dragged.right) // untouched
        assertClose(0.8f, dragged.bottom) // untouched
    }

    @Test
    fun `an edge handle only moves its own edge when free`() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = crop.dragged(CropHandle.RIGHT, dxNorm = 0.1f, dyNorm = 0.3f)
        assertClose(0.9f, dragged.right)
        assertClose(0.2f, dragged.left)
        assertClose(0.2f, dragged.top) // TOP/BOTTOM untouched by a RIGHT drag
        assertClose(0.8f, dragged.bottom)
    }

    @Test
    fun `a handle cannot shrink the box past its minimum size`() {
        val crop = CropRect(0.4f, 0.4f, 0.5f, 0.5f)
        val dragged = crop.dragged(CropHandle.BOTTOM_RIGHT, dxNorm = -0.5f, dyNorm = -0.5f)
        assertTrue(dragged.width >= 0.049f)
        assertTrue(dragged.height >= 0.049f)
    }

    @Test
    fun `a corner drag with a locked aspect keeps that aspect and anchors the opposite corner`() {
        val crop = CropRect(0.2f, 0.2f, 0.6f, 0.6f) // 0.4 x 0.4, square
        // Drag the bottom-right corner outward mostly horizontally.
        val dragged = crop.dragged(CropHandle.BOTTOM_RIGHT, dxNorm = 0.2f, dyNorm = 0.02f, lockedAspect = 2f)
        assertClose(0.2f, dragged.left) // anchor: the opposite corner
        assertClose(0.2f, dragged.top)
        assertClose(2f, dragged.width / dragged.height, tolerance = 0.01f)
    }

    @Test
    fun `an edge drag with a locked aspect stays centred on the perpendicular axis`() {
        val crop = CropRect(0.3f, 0.3f, 0.7f, 0.7f) // centre (0.5, 0.5), 0.4 x 0.4
        val dragged = crop.dragged(CropHandle.BOTTOM, dxNorm = 0f, dyNorm = 0.1f, lockedAspect = 1f)
        assertClose(1f, dragged.width / dragged.height, tolerance = 0.01f)
        assertClose(0.5f, (dragged.left + dragged.right) / 2f)
    }

    @Test
    fun `normalizedAspect converts a real-world ratio into this rect's own space`() {
        // A square (1:1) crop on a 2:1 wide image is twice as tall as it is wide in NORMALISED
        // space, because normalised X spans twice the real-world distance Y does.
        assertClose(0.5f, normalizedAspect(wantedAspect = 1f, imageAspect = 2f))
        assertClose(1f, normalizedAspect(wantedAspect = 16f / 9f, imageAspect = 16f / 9f))
    }
}
