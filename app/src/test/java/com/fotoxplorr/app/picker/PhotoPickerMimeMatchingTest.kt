package com.fotoxplorr.app.picker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [matchesRequestedMimeType] is the pure part of P0-20's mime scoping -- no `Uri`/`Context`
 * dependency, so it needs no Robolectric.
 */
class PhotoPickerMimeMatchingTest {

    @Test
    fun `null or blank requested type matches everything`() {
        assertTrue(matchesRequestedMimeType("image/jpeg", null))
        assertTrue(matchesRequestedMimeType("video/mp4", ""))
        assertTrue(matchesRequestedMimeType("image/jpeg", "  "))
    }

    @Test
    fun `a bare wildcard matches everything`() {
        assertTrue(matchesRequestedMimeType("image/jpeg", "*/*"))
        assertTrue(matchesRequestedMimeType("video/mp4", "*/*"))
    }

    @Test
    fun `a type-level wildcard matches only that type, case-insensitively`() {
        assertTrue(matchesRequestedMimeType("image/jpeg", "image/*"))
        assertTrue(matchesRequestedMimeType("IMAGE/JPEG", "image/*"))
        assertFalse(matchesRequestedMimeType("video/mp4", "image/*"))
        assertTrue(matchesRequestedMimeType("video/mp4", "video/*"))
        assertFalse(matchesRequestedMimeType("image/png", "video/*"))
    }

    @Test
    fun `an exact requested type matches only that exact type, case-insensitively`() {
        assertTrue(matchesRequestedMimeType("image/gif", "image/gif"))
        assertTrue(matchesRequestedMimeType("IMAGE/GIF", "image/gif"))
        assertFalse(matchesRequestedMimeType("image/jpeg", "image/gif"))
    }
}
