package com.fotoxplorr.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditRecipeTest {

    @Test
    fun `a fresh recipe is the identity`() {
        assertTrue(VideoEditRecipe().isIdentity)
    }

    @Test
    fun `a trim range or a changed speed is not the identity`() {
        assertFalse(VideoEditRecipe(trimStartUs = 1_000_000L).isIdentity)
        assertFalse(VideoEditRecipe(trimEndUs = 5_000_000L).isIdentity)
        assertFalse(VideoEditRecipe(speedFactor = 2f).isIdentity)
    }

    @Test
    fun `an end before or at the start is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEditRecipe(trimStartUs = 5_000_000L, trimEndUs = 5_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoEditRecipe(trimStartUs = 5_000_000L, trimEndUs = 1_000_000L)
        }
    }

    @Test
    fun `a negative start or non-positive speed is refused`() {
        assertThrows(IllegalArgumentException::class.java) { VideoEditRecipe(trimStartUs = -1L) }
        assertThrows(IllegalArgumentException::class.java) { VideoEditRecipe(speedFactor = 0f) }
        assertThrows(IllegalArgumentException::class.java) { VideoEditRecipe(speedFactor = -1f) }
    }

    @Test
    fun `a sample before the trim start or at or after the trim end is outside the window`() {
        val recipe = VideoEditRecipe(trimStartUs = 1_000_000L, trimEndUs = 5_000_000L)
        assertFalse(isWithinTrim(999_999L, recipe))
        assertTrue(isWithinTrim(1_000_000L, recipe))
        assertTrue(isWithinTrim(4_999_999L, recipe))
        assertFalse(isWithinTrim(5_000_000L, recipe))
    }

    @Test
    fun `a null trim end means everything from the start onward is included`() {
        val recipe = VideoEditRecipe(trimStartUs = 1_000_000L)
        assertTrue(isWithinTrim(1_000_000L, recipe))
        assertTrue(isWithinTrim(Long.MAX_VALUE / 2, recipe))
    }

    @Test
    fun `at 1x speed with no trim a timestamp passes through unchanged`() {
        assertEquals(2_500_000L, exportedPresentationTimeUs(2_500_000L, VideoEditRecipe()))
    }

    @Test
    fun `trimming rebases the exported clip to start at zero`() {
        val recipe = VideoEditRecipe(trimStartUs = 2_000_000L)
        assertEquals(0L, exportedPresentationTimeUs(2_000_000L, recipe))
        assertEquals(1_000_000L, exportedPresentationTimeUs(3_000_000L, recipe))
    }

    @Test
    fun `doubling speed halves the exported timestamp`() {
        val recipe = VideoEditRecipe(speedFactor = 2f)
        assertEquals(500_000L, exportedPresentationTimeUs(1_000_000L, recipe))
    }

    @Test
    fun `halving speed doubles the exported timestamp`() {
        val recipe = VideoEditRecipe(speedFactor = 0.5f)
        assertEquals(2_000_000L, exportedPresentationTimeUs(1_000_000L, recipe))
    }

    @Test
    fun `trim and speed combine -- rebase first, then scale`() {
        val recipe = VideoEditRecipe(trimStartUs = 1_000_000L, speedFactor = 2f)
        // 3s in becomes 2s after rebasing to the trim start, then 1s after 2x speed.
        assertEquals(1_000_000L, exportedPresentationTimeUs(3_000_000L, recipe))
    }

    @Test
    fun `a doubled speed doubles the labelled sample rate`() {
        assertEquals(88_200, speedAdjustedSampleRate(44_100, 2f))
    }

    @Test
    fun `a halved speed halves the labelled sample rate`() {
        assertEquals(22_050, speedAdjustedSampleRate(44_100, 0.5f))
    }

    @Test
    fun `1x speed leaves the sample rate exactly as it was`() {
        assertEquals(44_100, speedAdjustedSampleRate(44_100, 1f))
    }

    @Test
    fun `a speed low enough to zero out the sample rate is refused rather than producing invalid audio`() {
        assertThrows(IllegalArgumentException::class.java) { speedAdjustedSampleRate(10, 0.01f) }
    }
}
