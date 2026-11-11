package com.fotoxplorr.app.reel

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The volume envelope laid over the soundtrack inside the export, run on plain sample arrays. */
class ReelAudioTest {
    private fun processor(plan: ReelAudioPlan, rate: Int, channels: Int): ReelFadeProcessor {
        val p = ReelFadeProcessor(plan)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return p
    }

    private fun run(p: ReelFadeProcessor, samples: ShortArray, chunk: Int = samples.size): ShortArray {
        val out = ArrayList<Short>()
        var at = 0
        while (at < samples.size) {
            val n = minOf(chunk, samples.size - at)
            val buffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buffer.putShort(samples[at + i])
            buffer.flip()
            p.queueInput(buffer)
            val result = p.getOutput()
            while (result.hasRemaining()) out += result.short
            at += n
        }
        return out.toShortArray()
    }

    private fun constant(count: Int, value: Int = 10_000) = ShortArray(count) { value.toShort() }

    private val fourSeconds = ReelAudioPlan("u", 0L, 4_000_000L, 2_000_000L, 1f)

    @Test
    fun `the first half is untouched and the second half falls to nothing`() {
        val out = run(processor(fourSeconds, 1_000, 1), constant(4_000))
        assertEquals(4_000, out.size)
        assertEquals(10_000, out[0].toInt())
        assertEquals(10_000, out[1_999].toInt())
        assertNear("a quarter of the way down the fade", 7_500, out[2_500].toInt(), 2)
        assertNear("half way", 5_000, out[3_000].toInt(), 2)
        assertTrue("the last sample is nearly nothing", out[3_999].toInt() in 0..12)
    }

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun `the fall never rises`() {
        val out = run(processor(fourSeconds, 1_000, 1), constant(4_000))
        for (i in 2_000 until out.size - 1) assertTrue("at $i", out[i + 1] <= out[i])
    }

    @Test
    fun `the whole sound is scaled by the gain`() {
        val plan = fourSeconds.copy(gain = 0.5f)
        val out = run(processor(plan, 1_000, 1), constant(4_000))
        assertEquals(5_000, out[0].toInt())
        assertEquals(5_000, out[1_999].toInt())
        assertNear("half way down at half volume", 2_500, out[3_000].toInt(), 2)
    }

    @Test
    fun `both channels of a stereo sound get the same volume at the same moment`() {
        val frames = 4_000
        val input = ShortArray(frames * 2) { if (it % 2 == 0) 10_000 else -8_000 }
        val out = run(processor(fourSeconds, 1_000, 2), input)
        assertEquals(frames * 2, out.size)
        for (f in 0 until frames) {
            val left = out[2 * f].toInt()
            val right = out[2 * f + 1].toInt()
            assertEquals("frame $f", left * -0.8, right.toDouble(), 2.0)
        }
        assertEquals(10_000, out[0].toInt())
        assertEquals(-8_000, out[1].toInt())
    }

    @Test
    fun `sound past the plan's length is silence`() {
        val out = run(processor(fourSeconds, 1_000, 1), constant(5_000))
        assertEquals(0, out[4_000].toInt())
        assertEquals(0, out[4_999].toInt())
    }

    @Test
    fun `feeding it in small pieces gives the same result as in one`() {
        val whole = run(processor(fourSeconds, 1_000, 1), constant(4_000))
        val pieces = run(processor(fourSeconds, 1_000, 1), constant(4_000), chunk = 333)
        assertEquals(whole.toList(), pieces.toList())
    }

    @Test
    fun `a flush starts the clock again`() {
        val p = processor(fourSeconds, 1_000, 1)
        val first = run(p, constant(4_000))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val second = run(p, constant(4_000))
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `a sound that is not 16-bit is refused, so the export reports a sound problem`() {
        val p = ReelFadeProcessor(fourSeconds)
        try {
            p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
            fail("expected a refusal")
        } catch (expected: UnhandledAudioFormatException) {
            // fine
        }
    }

    @Test
    fun `no overflow at full volume, whatever the sample`() {
        val plan = fourSeconds.copy(fadeOutUs = 0L)
        val samples = ShortArray(2_000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val out = run(processor(plan, 1_000, 1), samples)
        assertEquals(samples.toList(), out.toList())
    }

    @Test
    fun `an empty piece is passed over`() {
        val p = processor(fourSeconds, 1_000, 1)
        p.queueInput(ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()))
        assertTrue(!p.getOutput().hasRemaining())
    }
}
