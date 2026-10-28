package com.fotoxplorr.app.reel

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The title card as pixels: its size, its colours, that the words land inside it and never run off it. How
 * the letters LOOK (the font, how a long place name wraps, right-to-left text) has not been seen on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReelTitleCardTest {
    private val dark = 0xFF1B2330.toInt()

    private fun marked(bitmap: android.graphics.Bitmap, background: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (px[y * bitmap.width + x] != background) out += x to y
        return out
    }

    @Test
    fun `the card is the size asked, filled with its colour, with the words in the middle`() {
        val bitmap = ReelTitleCard.render("June 2025\nLisbon", dark, 640, 360)
        assertEquals(640, bitmap.width)
        assertEquals(360, bitmap.height)
        assertEquals(dark, bitmap.getPixel(0, 0))
        assertEquals(dark, bitmap.getPixel(639, 359))
        val ink = marked(bitmap, dark)
        assertTrue("some pixels are words", ink.size > 200)
        val minX = ink.minOf { it.first }
        val maxX = ink.maxOf { it.first }
        val minY = ink.minOf { it.second }
        val maxY = ink.maxOf { it.second }
        assertTrue("inside the side margins: $minX..$maxX", minX >= 64 - 2 && maxX <= 640 - 64 + 2)
        assertTrue("inside the top and bottom: $minY..$maxY", minY > 360 * 0.1 && maxY < 360 * 0.9)
        assertEquals("centred side to side", 320.0, (minX + maxX) / 2.0, 12.0)
        assertEquals("centred top to bottom", 180.0, (minY + maxY) / 2.0, 20.0)
    }

    @Test
    fun `a very long text shrinks to fit and stays inside the card`() {
        val long = "A very long title that goes on and on about a trip that took a whole summer\nand a place with a very long name indeed, in the hills"
        val bitmap = ReelTitleCard.render(long, dark, 360, 640)
        val ink = marked(bitmap, dark)
        assertTrue(ink.isNotEmpty())
        assertTrue(ink.minOf { it.first } >= 0 && ink.maxOf { it.first } < 360)
        assertTrue("fits the height: ${ink.minOf { it.second }}..${ink.maxOf { it.second }}", ink.minOf { it.second } >= 640 * 0.05 && ink.maxOf { it.second } <= 640 * 0.95)
    }

    @Test
    fun `no words is a plain card`() {
        val bitmap = ReelTitleCard.render("   ", dark, 64, 36)
        assertTrue(marked(bitmap, dark).isEmpty())
    }

    @Test
    fun `the background is made opaque`() {
        val bitmap = ReelTitleCard.render("x", 0x00336699, 64, 36)
        assertEquals(0xFF336699.toInt(), bitmap.getPixel(0, 0))
    }

    @Test
    fun `text is white on a dark card and near-black on a light one`() {
        assertEquals(Color.WHITE, ReelTitleCard.readableOn(dark))
        assertEquals(Color.WHITE, ReelTitleCard.readableOn(0xFF000000.toInt()))
        assertEquals(Color.WHITE, ReelTitleCard.readableOn(0xFFB00020.toInt()))
        assertFalse(ReelTitleCard.readableOn(0xFFFFFFFF.toInt()) == Color.WHITE)
        assertFalse(ReelTitleCard.readableOn(0xFFF2E8C9.toInt()) == Color.WHITE)
    }

    @Test
    fun `the words on a light card are dark, so they can be read`() {
        val light = 0xFFF2E8C9.toInt()
        val bitmap = ReelTitleCard.render("Summer", light, 320, 180)
        val ink = marked(bitmap, light)
        assertTrue(ink.isNotEmpty())
        val darkest = ink.minOf { (x, y) -> Color.red(bitmap.getPixel(x, y)) }
        assertTrue("darkest ink $darkest", darkest < 80)
    }
}
