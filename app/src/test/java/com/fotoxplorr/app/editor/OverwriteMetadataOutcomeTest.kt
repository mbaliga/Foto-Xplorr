package com.fotoxplorr.app.editor

import com.fotoxplorr.app.metadata.UncarriedMetadata
import com.fotoxplorr.app.metadata.uncarriedMetadataIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the metadata gate: how a copy's observations fold into one [MetadataCopy], what
 *  refuses a replace, and what [uncarriedMetadataIn] recognises in a JPEG's segments. */
class OverwriteMetadataOutcomeTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 0, 0)

    private fun photoshop(resourceId: Int, size: Int): ByteArray {
        val data = ByteArray(size)
        val padded = if (size % 2 == 0) data else data + 0
        return segment(
            0xED,
            ascii("Photoshop 3.0\u0000") + ascii("8BIM") + byteArrayOf((resourceId shr 8).toByte(), resourceId.toByte(), 0, 0) +
                byteArrayOf(0, 0, (size shr 8).toByte(), size.toByte()) + padded,
        )
    }

    // ---- outcome mapping ----

    @Test
    fun `nothing missing is COPIED`() {
        assertEquals(MetadataCopy.COPIED, metadataCopyOutcome(false, xmpSkipped = false, stagedLostXmp = false, uncarriedFound = false))
    }

    @Test
    fun `a skipped packet, an XMP the staged file lost, or uncarried segments are each PARTIAL`() {
        assertEquals(MetadataCopy.PARTIAL, metadataCopyOutcome(false, xmpSkipped = true, stagedLostXmp = false, uncarriedFound = false))
        assertEquals(MetadataCopy.PARTIAL, metadataCopyOutcome(false, xmpSkipped = false, stagedLostXmp = true, uncarriedFound = false))
        assertEquals(MetadataCopy.PARTIAL, metadataCopyOutcome(false, xmpSkipped = false, stagedLostXmp = false, uncarriedFound = true))
    }

    @Test
    fun `a redacted read outranks a partial one`() {
        assertEquals(MetadataCopy.REDACTED, metadataCopyOutcome(true, xmpSkipped = true, stagedLostXmp = true, uncarriedFound = true))
    }

    @Test
    fun `PARTIAL refuses a replace as lost metadata and says what kind`() {
        val refused = overwriteMetadataRefusal(MetadataCopy.PARTIAL)!!
        assertEquals(OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST, refused.reason)
        assertTrue(refused.message!!, "Save a copy" in refused.message!!)
        assertNull(overwriteMetadataRefusal(MetadataCopy.COPIED))
    }

    @Test
    fun `a copy that was only partial says so in its saved note`() {
        assertTrue(MetadataCopy.PARTIAL.savedNote().isNotEmpty())
    }

    // ---- uncarriedMetadataIn ----

    @Test
    fun `an IPTC-IIM resource in an APP13 block is found`() {
        assertEquals(setOf(UncarriedMetadata.IPTC_IIM), uncarriedMetadataIn(soi + photoshop(0x0404, 20) + sos))
    }

    @Test
    fun `a Photoshop block with no IPTC resource is not`() {
        // 0x040F is an ICC profile resource, 0x0409 a thumbnail.
        assertTrue(uncarriedMetadataIn(soi + photoshop(0x040F, 20) + sos).isEmpty())
        assertTrue(uncarriedMetadataIn(soi + photoshop(0x0409, 7) + sos).isEmpty())
    }

    @Test
    fun `an empty IPTC resource is not a loss`() {
        assertTrue(uncarriedMetadataIn(soi + photoshop(0x0404, 0) + sos).isEmpty())
    }

    @Test
    fun `an APP13 block that does not parse counts as carrying IPTC`() {
        val broken = segment(0xED, ascii("Photoshop 3.0\u0000") + ascii("junk that is no resource block"))
        assertEquals(setOf(UncarriedMetadata.IPTC_IIM), uncarriedMetadataIn(soi + broken + sos))
    }

    @Test
    fun `extended XMP is found`() {
        val extended = segment(0xE1, ascii("http://ns.adobe.com/xmp/extension/\u0000") + ByteArray(40))
        assertEquals(setOf(UncarriedMetadata.EXTENDED_XMP), uncarriedMetadataIn(soi + extended + sos))
    }

    @Test
    fun `the ordinary XMP and Exif segments are not a loss`() {
        val xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>"))
        val exif = segment(0xE1, ascii("Exif\u0000\u0000") + ByteArray(30))
        assertTrue(uncarriedMetadataIn(soi + exif + xmp + sos).isEmpty())
    }

    @Test
    fun `a marker string inside the image data is not mistaken for a segment`() {
        val pixels = ascii("Photoshop 3.0\u00008BIM") + byteArrayOf(0x04, 0x04)
        assertTrue(uncarriedMetadataIn(soi + sos + pixels).isEmpty())
    }

    @Test
    fun `a file that is not a JPEG answers nothing`() {
        assertTrue(uncarriedMetadataIn(ascii("RIFF....WEBPVP8 ")).isEmpty())
        assertTrue(uncarriedMetadataIn(ByteArray(0)).isEmpty())
    }
}
