package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XmpPacket] against real round trips, not just DOM-API calls in isolation -- the risk this
 * class exists to avoid (an editor silently destroying a professional's existing catalogue
 * metadata) only ever shows up after a serialize-then-reparse cycle, never in the in-memory
 * object alone.
 */
class XmpPacketTest {

    @Test
    fun `every field round-trips through serialize and parse`() {
        val packet = XmpPacket.empty()
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", "A morning at the market")
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "rights", "© 2026 Jane Doe")
        packet.setSeq(XmpPacket.DC_NS, "dc", "creator", listOf("Jane Doe"))
        packet.setBag(XmpPacket.DC_NS, "dc", "subject", listOf("market", "weekend", "bread"))
        packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 4)

        val reparsed = XmpPacket.parse(packet.serialize())
        assertTrue("serialized packet failed to re-parse as XML", reparsed != null)
        reparsed!!

        assertEquals("A morning at the market", reparsed.langAlt(XmpPacket.DC_NS, "description"))
        assertEquals("© 2026 Jane Doe", reparsed.langAlt(XmpPacket.DC_NS, "rights"))
        assertEquals(listOf("Jane Doe"), reparsed.seq(XmpPacket.DC_NS, "creator"))
        assertEquals(listOf("market", "weekend", "bread"), reparsed.bag(XmpPacket.DC_NS, "subject"))
        assertEquals(4, reparsed.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `an empty packet answers every getter with nothing, not a crash`() {
        val packet = XmpPacket.empty()
        assertNull(packet.langAlt(XmpPacket.DC_NS, "description"))
        assertEquals(emptyList<String>(), packet.seq(XmpPacket.DC_NS, "creator"))
        assertEquals(emptyList<String>(), packet.bag(XmpPacket.DC_NS, "subject"))
        assertNull(packet.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `setting a value to blank or null removes the property rather than writing an empty one`() {
        val packet = XmpPacket.empty()
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", "something")
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", "")
        assertNull(packet.langAlt(XmpPacket.DC_NS, "description"))

        packet.setBag(XmpPacket.DC_NS, "dc", "subject", listOf("a"))
        packet.setBag(XmpPacket.DC_NS, "dc", "subject", emptyList())
        assertEquals(emptyList<String>(), packet.bag(XmpPacket.DC_NS, "subject"))

        packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 5)
        packet.setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", null)
        assertNull(packet.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `re-setting a field replaces it rather than appending a second copy`() {
        val packet = XmpPacket.empty()
        packet.setSeq(XmpPacket.DC_NS, "dc", "creator", listOf("First Name"))
        packet.setSeq(XmpPacket.DC_NS, "dc", "creator", listOf("Second Name"))
        assertEquals(listOf("Second Name"), packet.seq(XmpPacket.DC_NS, "creator"))

        val serialized = packet.serialize()
        assertEquals(1, Regex("<dc:creator[ >]").findAll(serialized).count())
    }

    /**
     * The property this whole class exists for: editing ONE field on a packet a real tool wrote
     * must leave every OTHER field -- including ones this class has never heard of -- exactly as
     * it found them. `crs:` is Adobe Camera Raw's own develop-settings namespace; a caption editor
     * has no business knowing what it means, only that it must survive.
     */
    @Test
    fun `editing one field never touches a property this class does not model`() {
        val existingFromAnotherTool = """
            <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/">
               <dc:creator><rdf:Seq><rdf:li>Original Photographer</rdf:li></rdf:Seq></dc:creator>
               <crs:WhiteBalance>Custom</crs:WhiteBalance>
               <crs:Temperature>5500</crs:Temperature>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val packet = XmpPacket.parse(existingFromAnotherTool)
        assertTrue("a real-shaped packet from another tool failed to parse", packet != null)
        packet!!

        packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", "Added by Foto Xplorr")
        val serialized = packet.serialize()

        assertTrue("lost the OTHER tool's creator field", serialized.contains("Original Photographer"))
        assertTrue("lost an unmodeled crs: property", serialized.contains("<crs:WhiteBalance>Custom</crs:WhiteBalance>"))
        assertTrue("lost an unmodeled crs: property", serialized.contains("<crs:Temperature>5500</crs:Temperature>"))

        val reparsed = XmpPacket.parse(serialized)!!
        assertEquals("Added by Foto Xplorr", reparsed.langAlt(XmpPacket.DC_NS, "description"))
        assertEquals(listOf("Original Photographer"), reparsed.seq(XmpPacket.DC_NS, "creator"))
    }

    @Test
    fun `rating written as an attribute by another tool is still read correctly`() {
        val attributeForm = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="3"/>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val packet = XmpPacket.parse(attributeForm)
        assertTrue(packet != null)
        assertEquals(3, packet!!.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `a keyword read back under a different prefix for the same namespace still matches`() {
        // A file using "xap" instead of the conventional "xmp" prefix for the identical
        // namespace URI -- proving lookups go by namespace, not by whatever string prefix one
        // particular tool happened to choose.
        val otherPrefix = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/" xap:Rating="2"/>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val packet = XmpPacket.parse(otherPrefix)!!
        assertEquals(2, packet.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    /**
     * The property [XmpPacket.serialize]'s own doc explains in full: `ExifInterface.setAttribute`
     * has no way to carry a non-ASCII character without corrupting it (confirmed against the real
     * jar, not assumed), so the serialized packet itself must be pure ASCII for this class to be
     * usable for its actual purpose at all. This is checked at the string level, byte by byte,
     * rather than trusted from the round-trip test above passing -- a round trip could pass by
     * accident (e.g. if some OTHER layer silently fixed the encoding) while the packet handed to
     * `setAttribute` was still unsafe.
     */
    @Test
    fun `serialized output is pure ASCII even when the content is not`() {
        val packet = XmpPacket.empty()
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "rights", "© 2026 José García")
        val serialized = packet.serialize()

        assertTrue("serialized XMP must be pure ASCII for ExifInterface.setAttribute", serialized.all { it.code <= 127 })
        assertTrue(serialized.contains("&#169;")) // ©
        assertTrue(serialized.contains("&#233;")) // é

        val reparsed = XmpPacket.parse(serialized)!!
        assertEquals("© 2026 José García", reparsed.langAlt(XmpPacket.DC_NS, "rights"))
    }

    @Test
    fun `a character outside the basic multilingual plane round-trips as one character, not two`() {
        // U+1F600 GRINNING FACE -- a real Unicode codepoint stored as a UTF-16 surrogate pair,
        // exactly the case a naive Char-by-Char escaper would split into two bogus references.
        val emoji = String(Character.toChars(0x1F600))
        val packet = XmpPacket.empty()
        packet.setLangAlt(XmpPacket.DC_NS, "dc", "description", "caption $emoji")

        val reparsed = XmpPacket.parse(packet.serialize())!!
        assertEquals("caption $emoji", reparsed.langAlt(XmpPacket.DC_NS, "description"))
    }

    @Test
    fun `garbage input is reported as unparseable, never silently treated as empty`() {
        assertNull(XmpPacket.parse("this is not xml at all { } <<<"))
    }

    @Test
    fun `serialized output carries a spec-standard xpacket wrapper`() {
        val serialized = XmpPacket.empty().serialize()
        assertTrue(serialized.startsWith("<?xpacket begin="))
        assertTrue(serialized.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
        assertTrue(serialized.contains("W5M0MpCehiHzreSzNTczkc9d"))
    }
}
