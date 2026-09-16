package com.fotoxplorr.app.audiotags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioTagsTest {

    @Test
    fun `equals and hashCode compare cover art by content, not reference`() {
        val a = AudioTags(title = "Song", coverArt = byteArrayOf(1, 2, 3))
        val b = AudioTags(title = "Song", coverArt = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different cover art bytes compare unequal`() {
        val a = AudioTags(coverArt = byteArrayOf(1, 2, 3))
        val b = AudioTags(coverArt = byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `null cover art on one side never equals present cover art on the other`() {
        val withCover = AudioTags(coverArt = byteArrayOf(1))
        val withoutCover = AudioTags(coverArt = null)
        assertNotEquals(withCover, withoutCover)
        assertNotEquals(withoutCover, withCover)
    }

    @Test
    fun `two tags with no cover art at all are equal`() {
        assertEquals(AudioTags(title = "x"), AudioTags(title = "x"))
    }
}
