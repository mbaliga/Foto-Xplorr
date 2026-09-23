package com.fotoxplorr.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** [requiredSpillBytes]'s own formula, pinned directly (P0-11) — [VideoTranscoder.checkFreeSpace]
 *  is what actually calls [android.os.StatFs], not testable without a real device; this is the
 *  pure arithmetic behind that refusal. */
class RequiredSpillBytesTest {

    @Test
    fun `zero duration still requires the fixed margin`() {
        assertEquals(100_000_000L, requiredSpillBytes(maxBitRateBitsPerSecond = 20_000_000, durationMs = 0L))
    }

    @Test
    fun `a ten-minute clip at the 20 Mbps ceiling`() {
        val tenMinutesMs = 10 * 60 * 1_000L
        // 20_000_000 bits/s -> 2_500_000 bytes/s; 2x for headroom; 600s duration; + 100 MB margin.
        val expected = 2L * 2_500_000L * 600L + 100_000_000L
        assertEquals(expected, requiredSpillBytes(maxBitRateBitsPerSecond = 20_000_000, durationMs = tenMinutesMs))
    }

    @Test
    fun `duration scales linearly`() {
        val oneMinute = requiredSpillBytes(maxBitRateBitsPerSecond = 8_000_000, durationMs = 60_000L)
        val twoMinutes = requiredSpillBytes(maxBitRateBitsPerSecond = 8_000_000, durationMs = 120_000L)
        assertEquals(oneMinute - 100_000_000L, (twoMinutes - 100_000_000L) / 2)
    }
}
