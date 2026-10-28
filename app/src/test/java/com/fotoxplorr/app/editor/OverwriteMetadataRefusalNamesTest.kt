package com.fotoxplorr.app.editor

import com.fotoxplorr.app.metadata.UncarriedMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refused replace names what it would lose when it knows. */
class OverwriteMetadataRefusalNamesTest {

    @Test
    fun `a partial copy with known categories names them by label`() {
        val refused = overwriteMetadataRefusal(
            MetadataCopy.PARTIAL,
            setOf(UncarriedMetadata.PNG_TEXT, UncarriedMetadata.IPTC_IIM),
        )!!

        val message = refused.message!!
        assertEquals(OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST, refused.reason)
        assertTrue(message, UncarriedMetadata.IPTC_IIM.label in message)
        assertTrue(message, UncarriedMetadata.PNG_TEXT.label in message)
        assertTrue(message, "Save a copy" in message)
        assertFalse("an unrelated category is not blamed: $message", UncarriedMetadata.WEBP_CHUNK.label in message)
    }

    @Test
    fun `one category reads without a conjunction`() {
        val message = overwriteMetadataRefusal(MetadataCopy.PARTIAL, setOf(UncarriedMetadata.EXTENDED_XMP))!!.message!!

        assertTrue(message, "extended XMP cannot be carried over" in message)
        assertFalse(message, " and extended XMP" in message)
    }

    @Test
    fun `a partial copy with no known category keeps the general wording`() {
        val message = overwriteMetadataRefusal(MetadataCopy.PARTIAL)!!.message!!

        assertTrue(message, "XMP packet" in message)
    }

    @Test
    fun `the categories are listed in a stable order as a sentence part`() {
        assertEquals(
            "IPTC captions and keywords, PNG text such as a generation prompt and other WebP metadata",
            setOf(UncarriedMetadata.WEBP_CHUNK, UncarriedMetadata.IPTC_IIM, UncarriedMetadata.PNG_TEXT).describeUncarried(),
        )
        assertEquals("", emptySet<UncarriedMetadata>().describeUncarried())
    }
}
