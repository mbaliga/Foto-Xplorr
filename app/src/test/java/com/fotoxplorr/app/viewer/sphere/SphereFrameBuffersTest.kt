package com.fotoxplorr.app.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A look-around reuses two frame bitmaps instead of allocating one per frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SphereFrameBuffersTest {
    @Test
    fun `frames of one size alternate between the same two bitmaps`() {
        val buffers = SphereFrameBuffers()
        val px = IntArray(6) { 0xFF112233.toInt() }
        val a = buffers.fill(px, 3, 2)
        val b = buffers.fill(px, 3, 2)
        val c = buffers.fill(px, 3, 2)
        val d = buffers.fill(px, 3, 2)
        assertNotSame(a, b)
        assertSame(a, c)
        assertSame(b, d)
    }

    @Test
    fun `each frame carries the pixels it was filled with`() {
        val buffers = SphereFrameBuffers()
        buffers.fill(IntArray(4) { 0xFF0000FF.toInt() }, 2, 2)
        buffers.fill(IntArray(4) { 0xFF00FF00.toInt() }, 2, 2)
        val third = buffers.fill(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        assertEquals(0xFFFF0000.toInt(), third.getPixel(1, 1))
    }

    @Test
    fun `a new size makes new bitmaps`() {
        val buffers = SphereFrameBuffers()
        val a = buffers.fill(IntArray(4), 2, 2)
        buffers.fill(IntArray(4), 2, 2)
        val c = buffers.fill(IntArray(6), 3, 2)
        assertNotSame(a, c)
        assertEquals(3, c.width)
    }
}
