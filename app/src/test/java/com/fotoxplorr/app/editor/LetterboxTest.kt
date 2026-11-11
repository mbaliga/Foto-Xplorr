package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTest {

    @Test
    fun `a wider-than-tall image in a square container letterboxes top and bottom`() {
        val rect = letterboxRect(containerWidth = 200f, containerHeight = 200f, contentWidth = 400f, contentHeight = 200f)
        assertEquals(0f, rect.left)
        assertEquals(200f, rect.width)
        assertEquals(100f, rect.height)
        assertEquals(50f, rect.top)
    }

    @Test
    fun `a taller-than-wide image in a square container letterboxes left and right`() {
        val rect = letterboxRect(containerWidth = 200f, containerHeight = 200f, contentWidth = 100f, contentHeight = 400f)
        assertEquals(0f, rect.top)
        assertEquals(200f, rect.height)
        assertEquals(50f, rect.width)
        assertEquals(75f, rect.left)
    }

    @Test
    fun `matching aspect ratios fill the container exactly`() {
        val rect = letterboxRect(containerWidth = 300f, containerHeight = 150f, contentWidth = 600f, contentHeight = 300f)
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)
        assertEquals(300f, rect.width)
        assertEquals(150f, rect.height)
    }

    @Test
    fun `a degenerate size does not crash or divide by zero`() {
        val rect = letterboxRect(containerWidth = 100f, containerHeight = 100f, contentWidth = 0f, contentHeight = 0f)
        assertEquals(100f, rect.width)
        assertEquals(100f, rect.height)
    }
}
