package com.fotoxplorr.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XmpPacket.clearProperty] and its read-side mirror [XmpPacket.hasProperty] against packets that
 * repeat a property: it is the removal half of "Save As strips location", where one surviving copy
 * is a leak.
 */
class XmpPacketClearTest {

    private val exif = XmpPacket.EXIF_NS

    private fun packet(vararg descriptions: String): XmpPacket = XmpPacket.parse(
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"${XmpPacket.RDF_NS}\">" +
            descriptions.joinToString("") + "</rdf:RDF></x:xmpmeta>",
    )!!

    @Test
    fun `an attribute on the second Description is cleared`() {
        val p = packet(
            "<rdf:Description rdf:about=\"\"/>",
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"$exif\" exif:GPSLatitude=\"51\"/>",
        )
        assertTrue(p.hasProperty(exif, "GPSLatitude"))

        p.clearProperty(exif, "GPSLatitude")

        assertFalse(p.hasProperty(exif, "GPSLatitude"))
        assertFalse(p.serialize().contains("GPSLatitude"))
    }

    @Test
    fun `every duplicate element goes, across Descriptions`() {
        val p = packet(
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"$exif\"><exif:GPSLatitude>1</exif:GPSLatitude><exif:GPSLatitude>2</exif:GPSLatitude></rdf:Description>",
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"$exif\"><exif:GPSLatitude>3</exif:GPSLatitude></rdf:Description>",
        )

        p.clearProperty(exif, "GPSLatitude")

        assertFalse(p.serialize().contains("GPSLatitude"))
    }

    @Test
    fun `a prefix containing a digit is matched by its namespace`() {
        val p = packet("<rdf:Description rdf:about=\"\" xmlns:ex2=\"$exif\" ex2:GPSLongitude=\"7\"><ex2:GPSAltitude>3</ex2:GPSAltitude></rdf:Description>")
        assertTrue(p.hasProperty(exif, "GPSLongitude"))
        assertTrue(p.hasProperty(exif, "GPSAltitude"))

        p.clearProperty(exif, "GPSLongitude")
        p.clearProperty(exif, "GPSAltitude")

        assertFalse(p.hasProperty(exif, "GPSLongitude"))
        assertFalse(p.hasProperty(exif, "GPSAltitude"))
    }

    @Test
    fun `a property nested inside a structure is cleared and found`() {
        val p = packet(
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"$exif\" xmlns:e=\"${XmpPacket.IPTC4XMPEXT_NS}\">" +
                "<e:LocationShown><rdf:Bag><rdf:li rdf:parseType=\"Resource\"><exif:GPSLatitude>1</exif:GPSLatitude></rdf:li></rdf:Bag></e:LocationShown></rdf:Description>",
        )
        assertTrue(p.hasProperty(exif, "GPSLatitude"))

        p.clearProperty(exif, "GPSLatitude")

        assertFalse(p.hasProperty(exif, "GPSLatitude"))
        assertTrue("the structure itself is not this call's business", p.hasProperty(XmpPacket.IPTC4XMPEXT_NS, "LocationShown"))
    }

    @Test
    fun `clearing leaves every other property alone`() {
        val p = packet(
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"$exif\" xmlns:xmp=\"${XmpPacket.XMP_NS}\" xmp:Rating=\"4\" exif:GPSLatitude=\"1\"/>",
        )

        p.clearProperty(exif, "GPSLatitude")

        assertEquals(4, p.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `clearing a property that was never there is a no-op`() {
        val p = packet("<rdf:Description rdf:about=\"\"/>")

        p.clearProperty(exif, "GPSLatitude")

        assertFalse(p.hasProperty(exif, "GPSLatitude"))
    }

    @Test
    fun `setting a rating replaces a stale attribute left on another Description`() {
        val p = packet(
            "<rdf:Description rdf:about=\"\"/>",
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"${XmpPacket.XMP_NS}\" xmp:Rating=\"2\"/>",
        )

        p.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 5)
        assertEquals(5, p.intValue(XmpPacket.XMP_NS, "Rating"))

        p.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", null)
        assertNull("no stale copy may answer after a clear", p.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `setting a caption removes a duplicate element in a later Description`() {
        val dc = XmpPacket.DC_NS
        val p = packet(
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"$dc\"><dc:subject><rdf:Bag><rdf:li>a</rdf:li></rdf:Bag></dc:subject></rdf:Description>",
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"$dc\"><dc:subject><rdf:Bag><rdf:li>b</rdf:li></rdf:Bag></dc:subject></rdf:Description>",
        )

        p.setBag(dc, "dc", "subject", emptyList())

        assertTrue(p.bag(dc, "subject").isEmpty())
    }
}
