package com.fotoxplorr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [floatPcmToInt16] pinned directly (P0-11) — a pure byte-format conversion, no [android.media.MediaCodec]
 *  needed. */
class AudioTranscoderTest {

    private fun floatBytesOf(vararg samples: Float): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun shortsOf(bytes: ByteArray): List<Short> {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
        return shorts.toList()
    }

    @Test
    fun `full-scale positive and negative samples map to the 16-bit extremes`() {
        val converted = shortsOf(floatPcmToInt16(floatBytesOf(1f, -1f)))
        assertEquals(listOf(Short.MAX_VALUE, (-Short.MAX_VALUE).toShort()), converted)
    }

    @Test
    fun `silence stays silence`() {
        assertEquals(listOf(0.toShort(), 0.toShort()), shortsOf(floatPcmToInt16(floatBytesOf(0f, 0f))))
    }

    @Test
    fun `out-of-range samples are clamped, not wrapped`() {
        val converted = shortsOf(floatPcmToInt16(floatBytesOf(1.5f, -2f)))
        assertEquals(listOf(Short.MAX_VALUE, (-Short.MAX_VALUE).toShort()), converted)
    }

    @Test
    fun `output has half as many bytes per sample as float input, per channel`() {
        val stereo = floatPcmToInt16(floatBytesOf(0.5f, -0.5f, 0.25f, -0.25f))
        assertEquals(8, stereo.size)
    }
}
