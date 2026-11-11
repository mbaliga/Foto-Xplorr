package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarSubtitlesTest {

    private fun candidate(name: String, path: String? = "Movies/") = SidecarCandidate(name, path, "content://x/$name")

    @Test
    fun `a matching stem and extension in the same bucket is found`() {
        val result = findSidecarSubtitles(
            listOf(candidate("Holiday.srt")),
            videoStem = "Holiday",
            videoRelativePath = "Movies/",
        )
        assertEquals(1, result.size)
        assertEquals("Holiday.srt", result.single().displayName)
    }

    @Test
    fun `every supported subtitle extension is matched`() {
        val candidates = listOf("clip.srt", "clip.vtt", "clip.ass", "clip.ssa").map { candidate(it) }
        val result = findSidecarSubtitles(candidates, videoStem = "clip", videoRelativePath = "Movies/")
        assertEquals(4, result.size)
    }

    @Test
    fun `stem matching is case-insensitive`() {
        val result = findSidecarSubtitles(listOf(candidate("HOLIDAY.SRT")), "holiday", "Movies/")
        assertEquals(1, result.size)
    }

    @Test
    fun `a file with a different stem is not a sidecar`() {
        val result = findSidecarSubtitles(listOf(candidate("OtherClip.srt")), "Holiday", "Movies/")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a matching stem in a different bucket is not a sidecar`() {
        val result = findSidecarSubtitles(
            listOf(candidate("Holiday.srt", path = "Movies/Archive/")),
            videoStem = "Holiday",
            videoRelativePath = "Movies/",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a non-subtitle extension is never matched even with a perfect stem`() {
        val result = findSidecarSubtitles(listOf(candidate("Holiday.jpg")), "Holiday", "Movies/")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a name with no extension at all is never matched`() {
        val result = findSidecarSubtitles(listOf(candidate("Holiday")), "Holiday", "Movies/")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fileStem strips exactly one trailing extension`() {
        assertEquals("Holiday", fileStem("Holiday.srt"))
        assertEquals("Holiday.2024", fileStem("Holiday.2024.srt"))
        assertEquals("noextension", fileStem("noextension"))
    }

    @Test
    fun `subtitleMimeTypeFor recognizes every supported extension and nothing else`() {
        assertTrue(subtitleMimeTypeFor("a.srt") != null)
        assertTrue(subtitleMimeTypeFor("a.vtt") != null)
        assertTrue(subtitleMimeTypeFor("a.ass") != null)
        assertTrue(subtitleMimeTypeFor("a.ssa") != null)
        assertEquals(null, subtitleMimeTypeFor("a.mp4"))
    }
}
