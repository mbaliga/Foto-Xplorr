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
    fun `1x speed returns the same number of frames unchanged`() {
        val mono = shortArrayOf(10, 20, 30, 40)
        assertEquals(listOf(10.toShort(), 20.toShort(), 30.toShort(), 40.toShort()), resamplePcm16(mono, 1, 1f).toList())
    }

    @Test
    fun `doubling speed halves the frame count`() {
        val mono = shortArrayOf(0, 100, 200, 300, 400, 500, 600, 700)
        val resampled = resamplePcm16(mono, 1, 2f)
        assertEquals(4, resampled.size)
    }

    @Test
    fun `halving speed doubles the frame count`() {
        val mono = shortArrayOf(0, 100, 200, 300)
        val resampled = resamplePcm16(mono, 1, 0.5f)
        assertEquals(8, resampled.size)
    }

    @Test
    fun `the first and last frames are preserved`() {
        val mono = shortArrayOf(1_000, 2_000, 3_000, 4_000, 5_000)
        val resampled = resamplePcm16(mono, 1, 0.5f)
        assertEquals(1_000.toShort(), resampled.first())
        assertEquals(5_000.toShort(), resampled.last())
    }

    @Test
    fun `a constant signal stays constant after resampling at any speed`() {
        val constant = ShortArray(50) { 1_234 }
        assertTrue(resamplePcm16(constant, 1, 1.7f).all { it == 1_234.toShort() })
        assertTrue(resamplePcm16(constant, 1, 0.3f).all { it == 1_234.toShort() })
    }

    @Test
    fun `stereo channels are resampled independently, never bleeding into each other`() {
        // Left channel counts up, right channel counts down -- if the two ever swapped or mixed,
        // this would no longer hold after resampling.
        val stereo = shortArrayOf(
            0, 100,
            10, 90,
            20, 80,
            30, 70,
        )
        val resampled = resamplePcm16(stereo, 2, 2f)
        for (frame in resampled.indices step 2) {
            val left = resampled[frame]
            val right = resampled[frame + 1]
            assertTrue("left ($left) must never exceed right ($right) for this fixture", left <= right)
        }
    }

    @Test
    fun `an empty source resamples to empty, not a divide-by-zero crash`() {
        assertEquals(0, resamplePcm16(ShortArray(0), 1, 2f).size)
    }

    @Test
    fun `a non-positive speed is refused rather than producing invalid or infinite output`() {
        assertThrows(IllegalArgumentException::class.java) { resamplePcm16(shortArrayOf(1, 2), 1, 0f) }
        assertThrows(IllegalArgumentException::class.java) { resamplePcm16(shortArrayOf(1, 2), 1, -1f) }
    }
}
