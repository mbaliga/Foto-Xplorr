package com.fotoxplorr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmTimingTest {

    @Test
    fun `mono 16-bit PCM is two bytes per frame`() {
        assertEquals(1, pcmFrameCount(bytesWritten = 2, channelCount = 1))
        assertEquals(100, pcmFrameCount(bytesWritten = 200, channelCount = 1))
    }

    @Test
    fun `stereo 16-bit PCM is four bytes per frame`() {
        assertEquals(1, pcmFrameCount(bytesWritten = 4, channelCount = 2))
        assertEquals(100, pcmFrameCount(bytesWritten = 400, channelCount = 2))
    }

    /**
     * The property this function exists to get right without a test proving it by accident: a
     * partial frame at a chunk boundary is dropped from the COUNT, not rounded up into a frame
     * that was never actually written -- rounding up would claim more audio time elapsed than the
     * bytes actually represent, drifting every later timestamp early.
     */
    @Test
    fun `a partial trailing frame is not counted`() {
        assertEquals(1, pcmFrameCount(bytesWritten = 5, channelCount = 2)) // one full stereo frame + 1 stray byte
    }

    @Test
    fun `zero bytes is zero frames, not an error`() {
        assertEquals(0, pcmFrameCount(bytesWritten = 0, channelCount = 2))
    }

    @Test
    fun `a non-positive channel count is refused rather than dividing by zero or going negative`() {
        assertThrows(IllegalArgumentException::class.java) { pcmFrameCount(100, channelCount = 0) }
    }

    @Test
    fun `one second of frames at a sample rate is exactly one second in microseconds`() {
        assertEquals(1_000_000L, presentationTimeUsFor(frameCount = 44_100, sampleRate = 44_100))
        assertEquals(1_000_000L, presentationTimeUsFor(frameCount = 48_000, sampleRate = 48_000))
    }

    @Test
    fun `half a second of frames is half a second in microseconds`() {
        assertEquals(500_000L, presentationTimeUsFor(frameCount = 22_050, sampleRate = 44_100))
    }

    @Test
    fun `zero frames is zero microseconds`() {
        assertEquals(0L, presentationTimeUsFor(frameCount = 0, sampleRate = 44_100))
    }

    @Test
    fun `a non-positive sample rate is refused rather than dividing by zero`() {
        assertThrows(IllegalArgumentException::class.java) { presentationTimeUsFor(1_000, sampleRate = 0) }
    }
}
