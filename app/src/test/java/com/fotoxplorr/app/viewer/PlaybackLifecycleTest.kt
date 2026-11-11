package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle gate on starting a video, and the seek clamp, as plain JUnit. The composed side of
 * the gate (a player that arms after the activity stopped stays a poster) is in
 * `VideoPlayerLifecycleTest`.
 */
class PlaybackLifecycleTest {

    // ---- started gate -----------------------------------------------------------------------

    @Test
    fun `a stopped activity does not arm a player`() {
        assertFalse(mayArm(armed = false, scrubSuspended = false, started = false))
    }

    @Test
    fun `a started activity arms a settled player`() {
        assertTrue(mayArm(armed = false, scrubSuspended = false, started = true))
    }

    @Test
    fun `started alone never arms past a finger on the strip or an armed player`() {
        assertFalse(mayArm(armed = false, scrubSuspended = true, started = true))
        assertFalse(mayArm(armed = true, scrubSuspended = false, started = true))
    }

    @Test
    fun `playback may start while the lifecycle is started or the window is in picture-in-picture`() {
        assertTrue(playbackMayStart(lifecycleStarted = true, inPictureInPicture = false))
        assertTrue(playbackMayStart(lifecycleStarted = false, inPictureInPicture = true))
        assertFalse(playbackMayStart(lifecycleStarted = false, inPictureInPicture = false))
    }

    @Test
    fun `a new player wants to play only with autoplay on and the activity started`() {
        assertTrue(initialPlayWhenReady(autoplay = true, started = true))
        assertFalse(initialPlayWhenReady(autoplay = true, started = false))
        assertFalse(initialPlayWhenReady(autoplay = false, started = true))
        assertFalse(initialPlayWhenReady(autoplay = false, started = false))
    }

    // ---- seek clamp -------------------------------------------------------------------------

    @Test
    fun `a seek inside a known duration is kept`() {
        assertEquals(30_000L, clampSeekMs(30_000L, durationMs = 64_000L))
    }

    @Test
    fun `a seek past a known duration lands on the end and one before zero on zero`() {
        assertEquals(64_000L, clampSeekMs(90_000L, durationMs = 64_000L))
        assertEquals(0L, clampSeekMs(-10_000L, durationMs = 64_000L))
    }

    @Test
    fun `an unknown duration does not pin every seek to zero`() {
        // The regression: the upper bound was 0, so +10 s and "Resume from 1:23" both went to 0:00.
        assertEquals(83_000L, clampSeekMs(83_000L, durationMs = 0L))
        assertEquals(10_000L, clampSeekMs(10_000L, durationMs = -1L))
        assertEquals(0L, clampSeekMs(-5_000L, durationMs = 0L))
    }
}
