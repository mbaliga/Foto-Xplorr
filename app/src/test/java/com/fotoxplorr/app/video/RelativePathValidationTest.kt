package com.fotoxplorr.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativePathValidationTest {

    // ── Video collection ─────────────────────────────────────────────────────────────

    @Test
    fun `a video path under DCIM, Pictures or Movies is kept as-is`() {
        assertEquals("DCIM/Camera/", validatedVideoRelativePath("DCIM/Camera/"))
        assertEquals("Pictures/Trips/", validatedVideoRelativePath("Pictures/Trips/"))
        assertEquals("Movies/", validatedVideoRelativePath("Movies/"))
    }

    @Test
    fun `an illegal video path falls back to the app's own Movies folder`() {
        assertEquals(FALLBACK_VIDEO_RELATIVE_PATH, validatedVideoRelativePath("Download/"))
        assertEquals(FALLBACK_VIDEO_RELATIVE_PATH, validatedVideoRelativePath("Android/media/com.other.app/"))
    }

    @Test
    fun `a null or blank video path falls back rather than crashing`() {
        assertEquals(FALLBACK_VIDEO_RELATIVE_PATH, validatedVideoRelativePath(null))
        assertEquals(FALLBACK_VIDEO_RELATIVE_PATH, validatedVideoRelativePath("   "))
    }

    @Test
    fun `video path matching is case-insensitive on the prefix`() {
        assertEquals("dcim/Camera/", validatedVideoRelativePath("dcim/Camera/"))
    }

    // ── Audio collection ─────────────────────────────────────────────────────────────

    @Test
    fun `an audio path under any of the six legal primaries is kept as-is`() {
        listOf("Music/", "Podcasts/", "Ringtones/", "Alarms/", "Notifications/", "Recordings/").forEach { path ->
            assertEquals(path, validatedAudioRelativePath(path))
        }
    }

    @Test
    fun `an illegal audio path falls back to the app's own Music folder`() {
        assertEquals(FALLBACK_AUDIO_RELATIVE_PATH, validatedAudioRelativePath("Download/"))
        assertEquals(FALLBACK_AUDIO_RELATIVE_PATH, validatedAudioRelativePath("DCIM/Camera/"))
    }

    @Test
    fun `a null audio path falls back rather than crashing`() {
        assertEquals(FALLBACK_AUDIO_RELATIVE_PATH, validatedAudioRelativePath(null))
    }
}
