package com.fotoxplorr.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0-02: ISO 6709 parsing for `MediaMetadataRetriever`'s `METADATA_KEY_LOCATION` video string,
 * pulled out as a pure function so every shape it needs to handle (or reject) is pinned here
 * rather than only reachable through a real video file.
 */
class Iso6709ParsingTest {

    @Test
    fun `latitude and longitude only, northern and eastern hemispheres`() {
        val location = parseIso6709("+27.5916+086.5640/")
        assertEquals(27.5916, location?.latitude!!, 0.0001)
        assertEquals(86.5640, location.longitude, 0.0001)
        assertNull(location.altitudeMeters)
    }

    @Test
    fun `with an altitude component`() {
        val location = parseIso6709("+27.5916+086.5640+8850/")
        assertEquals(27.5916, location?.latitude!!, 0.0001)
        assertEquals(86.5640, location.longitude, 0.0001)
        assertEquals(8850.0, location.altitudeMeters!!, 0.0001)
    }

    @Test
    fun `southern hemisphere latitude`() {
        val location = parseIso6709("-33.8688+151.2093/")
        assertEquals(-33.8688, location?.latitude!!, 0.0001)
        assertEquals(151.2093, location.longitude, 0.0001)
    }

    @Test
    fun `western hemisphere longitude`() {
        val location = parseIso6709("+40.6892-074.0445/")
        assertEquals(40.6892, location?.latitude!!, 0.0001)
        assertEquals(-74.0445, location.longitude, 0.0001)
    }

    @Test
    fun `southern and western together, with altitude`() {
        val location = parseIso6709("-22.9068-043.1729+2/")
        assertEquals(-22.9068, location?.latitude!!, 0.0001)
        assertEquals(-43.1729, location.longitude, 0.0001)
        assertEquals(2.0, location.altitudeMeters!!, 0.0001)
    }

    @Test
    fun `garbage input parses to null`() {
        assertNull(parseIso6709("not a coordinate"))
        assertNull(parseIso6709(""))
    }

    @Test
    fun `the all-zero coordinate is treated as no location`() {
        assertNull(parseIso6709("+0.0000+000.0000/"))
    }

    @Test
    fun `latitude out of range is rejected`() {
        assertNull(parseIso6709("+90.0001+000.0000/"))
        assertNull(parseIso6709("-91.0000+000.0000/"))
    }

    @Test
    fun `longitude out of range is rejected`() {
        assertNull(parseIso6709("+45.0000+180.0001/"))
        assertNull(parseIso6709("+45.0000-181.0000/"))
    }

    @Test
    fun `boundary values are accepted`() {
        val location = parseIso6709("+90.0000+180.0000/")
        assertEquals(90.0, location?.latitude!!, 0.0001)
        assertEquals(180.0, location.longitude, 0.0001)
    }
}
