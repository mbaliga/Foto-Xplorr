package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [momentFraction] and [highlightSegments] are the scrubber's whole geometry: where the marker
 * sits, and which stretches of the track paint in the highlight colour. Both are the kind of
 * arithmetic that goes subtly wrong at the edges -- a duration of zero, a moment past the end, two
 * markers close enough to touch -- which is exactly what is pinned down here rather than
 * eyeballed, mirroring [LiveTextGeometryTest]'s split for the same reason.
 */
class MomentScrubberGeometryTest {

    private fun moment(positionMs: Long, source: MomentSource = MomentSource.AUTO) =
        VideoMoment(mediaId = MediaId(1L), positionMs = positionMs, source = source)

    // ---- momentFraction ----

    @Test
    fun `a moment halfway through the video sits at fraction one half`() {
        assertEquals(0.5f, momentFraction(5_000L, 10_000L), 1e-4f)
    }

    @Test
    fun `a zero duration does not divide by zero`() {
        assertEquals(0f, momentFraction(0L, 0L), 1e-4f)
        assertEquals(0f, momentFraction(500L, 0L), 1e-4f)
    }

    @Test
    fun `a position past the duration clamps to the end rather than overshooting`() {
        assertEquals(1f, momentFraction(15_000L, 10_000L), 1e-4f)
    }

    @Test
    fun `a negative position clamps to the start`() {
        assertEquals(0f, momentFraction(-500L, 10_000L), 1e-4f)
    }

    // ---- highlightSegments ----

    @Test
    fun `no moments means no segments`() {
        assertTrue(highlightSegments(emptyList(), 10_000L, 500L).isEmpty())
    }

    @Test
    fun `a zero duration returns no segments rather than dividing by zero`() {
        assertTrue(highlightSegments(listOf(moment(1_000L)), 0L, 500L).isEmpty())
    }

    @Test
    fun `one moment produces one segment centred on it`() {
        val segments = highlightSegments(listOf(moment(5_000L)), 10_000L, 1_000L)

        assertEquals(1, segments.size)
        assertEquals(0.4f, segments[0].startFraction, 1e-4f)
        assertEquals(0.6f, segments[0].endFraction, 1e-4f)
    }

    @Test
    fun `a moment beyond the duration is clamped, not dropped or left overshooting`() {
        val segments = highlightSegments(listOf(moment(50_000L)), 10_000L, 1_000L)

        assertEquals(1, segments.size)
        assertEquals(0.9f, segments[0].startFraction, 1e-4f)
        assertEquals(1f, segments[0].endFraction, 1e-4f)
    }

    @Test
    fun `two moments far apart stay as two separate segments`() {
        val segments = highlightSegments(listOf(moment(1_000L), moment(9_000L)), 10_000L, 500L)

        assertEquals(2, segments.size)
    }

    @Test
    fun `two moments whose windows overlap merge into one segment`() {
        // window a: 1500..2500, window b: 2300..3300 -- they overlap between 2300 and 2500.
        val segments = highlightSegments(listOf(moment(2_000L), moment(2_800L)), 10_000L, 500L)

        assertEquals(1, segments.size)
        assertEquals(0.15f, segments[0].startFraction, 1e-4f)
        assertEquals(0.33f, segments[0].endFraction, 1e-4f)
    }

    @Test
    fun `two moments whose windows touch exactly at the boundary still merge`() {
        // window a: 1500..2500, window b: 2500..3500 -- they share only the single point 2500.
        val segments = highlightSegments(listOf(moment(2_000L), moment(3_000L)), 10_000L, 500L)

        assertEquals(1, segments.size)
        assertEquals(0.15f, segments[0].startFraction, 1e-4f)
        assertEquals(0.35f, segments[0].endFraction, 1e-4f)
    }

    @Test
    fun `segments come out in position order regardless of the input order`() {
        val segments = highlightSegments(listOf(moment(9_000L), moment(1_000L)), 10_000L, 200L)

        assertEquals(2, segments.size)
        assertTrue(segments[0].startFraction < segments[1].startFraction)
    }

    @Test
    fun `three moments where only the first two overlap produce two segments`() {
        // a: 900..1100, b: 1050..1250 (merges with a into 900..1250), c: 8000..8200 (separate).
        val segments = highlightSegments(
            listOf(moment(1_000L), moment(1_150L), moment(8_100L)),
            10_000L,
            100L,
        )

        assertEquals(2, segments.size)
        assertEquals(0.09f, segments[0].startFraction, 1e-4f)
        assertEquals(0.125f, segments[0].endFraction, 1e-4f)
    }

    // ---- formatTimecode ----

    @Test
    fun `formats minutes and seconds without a leading zero on the minutes`() {
        assertEquals("0:04", formatTimecode(4_000L))
        assertEquals("0:20", formatTimecode(20_000L))
        assertEquals("1:05", formatTimecode(65_000L))
    }

    @Test
    fun `a negative value clamps to zero rather than printing a minus sign`() {
        assertEquals("0:00", formatTimecode(-1L))
    }
}
