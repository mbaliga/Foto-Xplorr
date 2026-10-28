package com.fotoxplorr.app.animate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The small value types a screen builds a request from refuse nonsense where it is made, not deep in a job. */
class AnimateInputTest {
    private fun refuses(block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            // fine
        }
    }

    @Test
    fun `a still stays up for a sensible time`() {
        AnimStill(AnimateKit.asset(1), 1)
        AnimStill(AnimateKit.asset(1), AnimStill.MAX_HOLD_MS)
        refuses { AnimStill(AnimateKit.asset(1), 0) }
        refuses { AnimStill(AnimateKit.asset(1), AnimStill.MAX_HOLD_MS + 1) }
    }

    @Test
    fun `a transition has a bounded number of frames`() {
        StillTransition(0)
        StillTransition(StillTransition.MAX_FRAMES)
        refuses { StillTransition(-1) }
        refuses { StillTransition(StillTransition.MAX_FRAMES + 1) }
        refuses { StillTransition(3, frameMs = 0) }
    }

    @Test
    fun `a clip has a length and a rate in range`() {
        val v = AnimateKit.video(1)
        AnimateInput.Clip(v, 0, 1)
        refuses { AnimateInput.Clip(v, 500, 500) }
        refuses { AnimateInput.Clip(v, -1, 500) }
        refuses { AnimateInput.Clip(v, 0, 500, fps = 0.5) }
        refuses { AnimateInput.Clip(v, 0, 500, fps = 120.0) }
    }

    @Test
    fun `the photos and videos an input reads are listed once each, for the protection checks`() {
        val a = AnimateKit.asset(1)
        val b = AnimateKit.asset(2)
        val stills = AnimateInput.Stills(listOf(AnimStill(a, 100), AnimStill(b, 100), AnimStill(a, 100)))
        assertEquals(listOf(1L, 2L), stills.photos.map { it.id.value })
        assertTrue(stills.videos.isEmpty())
        val clip = AnimateInput.Clip(AnimateKit.video(5), 0, 100)
        assertTrue(clip.photos.isEmpty())
        assertEquals(listOf(5L), clip.videos.map { it.id.value })
        val file = AnimateInput.Existing(AnimateKit.asset(7))
        assertEquals(listOf(7L), file.photos.map { it.id.value })
        assertTrue(file.videos.isEmpty())
    }

    @Test
    fun `the default ceilings differ for a video`() {
        assertEquals(480, AnimateDefaults.limits(AnimOutput.GIF).maxLongEdge)
        assertEquals(480, AnimateDefaults.limits(AnimOutput.APNG).maxLongEdge)
        assertTrue(AnimateDefaults.limits(AnimOutput.VIDEO).maxLongEdge > 480)
        assertTrue(AnimateDefaults.limits(AnimOutput.VIDEO).maxBytes > AnimateDefaults.limits(AnimOutput.GIF).maxBytes)
    }
}
