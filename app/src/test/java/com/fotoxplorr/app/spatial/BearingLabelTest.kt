package com.fotoxplorr.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The compass readout's arithmetic.
 *
 * Small, but the two ways it goes wrong are both invisible until someone turns around with the
 * phone: a sector boundary off by half a sector (so "N" starts at 0 instead of 337.5), and a
 * negative or wrapped bearing producing a nonsense label. Both are cheap to pin here and expensive
 * to notice on a device.
 */
class BearingLabelTest {

    @Test
    fun `each cardinal owns the sector centred on it, not the one after it`() {
        // The classic off-by-half-a-sector: north must run from 337.5 to 22.5, not 0 to 45.
        assertEquals("N", cardinalName(0f))
        assertEquals("N", cardinalName(22.4f))
        assertEquals("N", cardinalName(350f))
        assertEquals("NE", cardinalName(45f))
        assertEquals("E", cardinalName(90f))
        assertEquals("SE", cardinalName(135f))
        assertEquals("S", cardinalName(180f))
        assertEquals("SW", cardinalName(225f))
        assertEquals("W", cardinalName(270f))
        assertEquals("NW", cardinalName(315f))
    }

    @Test
    fun `just under 360 is still north`() {
        // 359.9 + 22.5 overflows past 360; without the modulo this indexes off the end of the list.
        assertEquals("N", cardinalName(359.9f))
        assertEquals("N", cardinalName(360f))
    }

    @Test
    fun `negative and over-wound bearings normalise`() {
        // The sensor is normalised upstream, but nothing in the type says so.
        assertEquals("W", cardinalName(-90f))
        assertEquals("E", cardinalName(450f))
        assertEquals("N", cardinalName(-720f))
    }

    @Test
    fun `the label is three padded degrees and a compass point`() {
        assertEquals("000° N", bearingLabel(0f))
        assertEquals("047° NE", bearingLabel(47f))
        assertEquals("180° S", bearingLabel(180f))
        assertEquals("359° N", bearingLabel(359f))
    }

    @Test
    fun `a bearing that rounds up to 360 reads as 000, not 360`() {
        // 359.7 rounds to 360, which is not a bearing anyone writes.
        assertEquals("000° N", bearingLabel(359.7f))
    }

    @Test
    fun `the degrees and the compass point never disagree`() {
        // The failure this guards: "090 N" -- two different normalisations of the same angle.
        var degrees = -400f
        while (degrees <= 800f) {
            val label = bearingLabel(degrees)
            val shown = label.substringBefore('°').toInt()
            assertEquals("at $degrees", cardinalName(shown.toFloat()), label.substringAfter(' '))
            degrees += 0.5f
        }
    }
}
