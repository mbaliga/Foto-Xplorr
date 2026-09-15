package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputFormatTest {

    @Test
    fun `a copy keeps the source's own format when it is one Bitmap can encode`() {
        assertEquals(OutputFormat.JPEG, outputFormatFor("image/jpeg"))
        assertEquals(OutputFormat.PNG, outputFormatFor("image/png"))
        assertEquals(OutputFormat.WEBP, outputFormatFor("image/webp"))
    }

    @Test
    fun `a copy of a source Bitmap cannot encode falls back to JPEG`() {
        // HEIC, GIF, an embedded RAW preview, and a null/unknown mime type all land here --
        // Bitmap.compress has no encoder for any of them, so "keep the original format" cannot be
        // honoured literally and JPEG is the safe universal answer, matching this editor's
        // long-standing default before format choice existed at all.
        assertEquals(OutputFormat.JPEG, outputFormatFor("image/heic"))
        assertEquals(OutputFormat.JPEG, outputFormatFor("image/gif"))
        assertEquals(OutputFormat.JPEG, outputFormatFor("application/octet-stream"))
        assertEquals(OutputFormat.JPEG, outputFormatFor(null))
    }

    @Test
    fun `overwriting in place only works for a source already in one of the three encodable formats`() {
        assertEquals(OutputFormat.JPEG, overwriteFormatFor("image/jpeg"))
        assertEquals(OutputFormat.PNG, overwriteFormatFor("image/png"))
        assertEquals(OutputFormat.WEBP, overwriteFormatFor("image/webp"))
    }

    /**
     * The property [overwriteFormatFor]'s own doc calls out: unlike [outputFormatFor], there is no
     * safe fallback here. A HEIC photo overwritten "as JPEG" would leave a file whose bytes no
     * longer match its own extension and MediaStore row -- worse than refusing, which is why this
     * returns null rather than [OutputFormat.JPEG] the way a fresh copy would.
     */
    @Test
    fun `overwriting a format with no matching OutputFormat is refused, not silently downgraded to JPEG`() {
        assertNull(overwriteFormatFor("image/heic"))
        assertNull(overwriteFormatFor("image/gif"))
        assertNull(overwriteFormatFor("application/octet-stream"))
        assertNull(overwriteFormatFor(null))
    }
}
