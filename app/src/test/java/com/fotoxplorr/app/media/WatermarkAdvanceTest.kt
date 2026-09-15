package com.fotoxplorr.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FX-003: the watermark's monotonic advance rule, under exactly the disorder a real device
 * produces — scan batches complete out of order, MediaStore hands back rows with older
 * modification times mid-delta, and a pass can legitimately see nothing at all.
 *
 * A watermark that moves backwards makes the next delta redo work it already did; one that
 * advances on a zero would swallow "MediaStore said nothing" as "the epoch". Both directions
 * are pinned here.
 */
class WatermarkAdvanceTest {

    @Test
    fun `a newer candidate advances the mark`() {
        assertEquals(2_000L, watermarkAdvance(current = 1_000L, candidate = 2_000L))
    }

    @Test
    fun `an older candidate never moves the mark backwards`() {
        assertNull(watermarkAdvance(current = 2_000L, candidate = 1_000L))
    }

    @Test
    fun `an equal candidate is not an advance`() {
        assertNull(watermarkAdvance(current = 2_000L, candidate = 2_000L))
    }

    @Test
    fun `zero and negative candidates are 'nothing seen', not the epoch`() {
        assertNull(watermarkAdvance(current = 0L, candidate = 0L))
        assertNull(watermarkAdvance(current = 5L, candidate = 0L))
        assertNull(watermarkAdvance(current = 5L, candidate = -1L))
    }

    @Test
    fun `out-of-order updates fold to the maximum, regardless of arrival order`() {
        // The same batch completions in three different arrival orders must land on the
        // same final mark — this is what "monotonic under out-of-order updates" means.
        val arrivals = listOf(
            listOf(3_000L, 1_000L, 2_000L),
            listOf(1_000L, 2_000L, 3_000L),
            listOf(2_000L, 3_000L, 1_000L, 3_000L),
        )
        arrivals.forEach { order ->
            var mark = 0L
            order.forEach { candidate ->
                watermarkAdvance(mark, candidate)?.let { mark = it }
            }
            assertEquals("arrival order $order", 3_000L, mark)
        }
    }
}
