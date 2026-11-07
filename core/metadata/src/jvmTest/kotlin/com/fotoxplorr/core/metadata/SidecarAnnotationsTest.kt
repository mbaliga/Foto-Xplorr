package com.fotoxplorr.core.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SidecarAnnotationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stamp = "2026-11-04T12:00:00Z"

    private val full = SidecarAnnotations(
        rating = 4,
        label = "Blue",
        flag = SidecarFlag.PICK,
        keywords = setOf("Travel", "Café"),
        keywordPaths = setOf("Places|Italy|Rome"),
        title = "Colosseum",
        caption = "Sunrise at the \"Colosseum\" & friends",
        creators = listOf("José García"),
        copyright = "© 2026 José",
        metadataDate = stamp,
    )

    private fun roundTrip(a: SidecarAnnotations): SidecarAnnotations {
        val bytes = XmpAnnotationCodec.update(null, a, stamp)!!
        return XmpAnnotationCodec.readBytes(bytes)!!
    }

    // ---- round trip ----

    @Test
    fun `every owned field survives serialize and parse`() {
        val back = roundTrip(full)
        assertEquals(4, back.rating)
        assertEquals("Blue", back.label)
        assertEquals(SidecarFlag.PICK, back.flag)
        // dc:subject also carries the path components, as Lightroom does
        assertTrue(back.keywords.containsAll(setOf("Travel", "Café", "Places", "Italy", "Rome")))
        assertEquals(setOf("Places|Italy|Rome"), back.keywordPaths)
        assertEquals("Colosseum", back.title)
        assertEquals(full.caption, back.caption)
        assertEquals(listOf("José García"), back.creators)
        assertEquals("© 2026 José", back.copyright)
        assertEquals(stamp, back.metadataDate)
    }

    @Test
    fun `reject is rating minus one and reads back as a flag not a star count`() {
        val bytes = XmpAnnotationCodec.update(null, SidecarAnnotations(flag = SidecarFlag.REJECT), stamp)!!
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("Rating=\"-1\""))
        val back = XmpAnnotationCodec.readBytes(bytes)!!
        assertNull(back.rating)
        assertEquals(SidecarFlag.REJECT, back.flag)
    }

    @Test
    fun `a rejected photo keeps its stars and the unflag clears the minus one`() {
        val rejected = roundTrip(SidecarAnnotations(rating = 2, flag = SidecarFlag.REJECT))
        assertEquals(2, rejected.rating)
        assertEquals(SidecarFlag.REJECT, rejected.flag)

        val first = XmpAnnotationCodec.update(null, SidecarAnnotations(flag = SidecarFlag.REJECT), stamp)!!
        val second = XmpAnnotationCodec.update(first, SidecarAnnotations(flag = SidecarFlag.NONE), "2026-11-05T00:00:00Z")!!
        val back = XmpAnnotationCodec.readBytes(second)!!
        assertNull(back.rating)
        assertNull(back.flag)
    }

    @Test
    fun `an Adobe style minus one rating alone reads as reject`() {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="-1"/></rdf:RDF></x:xmpmeta>"""
        val a = XmpAnnotationCodec.readBytes(xml.toByteArray())!!
        assertEquals(SidecarFlag.REJECT, a.flag)
        assertNull(a.rating)
    }

    @Test
    fun `clearing a scalar is explicit and null means leave alone`() {
        val written = XmpAnnotationCodec.update(null, full, stamp)!!
        val untouched = XmpAnnotationCodec.readBytes(
            XmpAnnotationCodec.update(written, SidecarAnnotations(), "2026-11-05T00:00:00Z")!!,
        )!!
        assertEquals(4, untouched.rating)
        assertEquals("Blue", untouched.label)
        val cleared = XmpAnnotationCodec.readBytes(
            XmpAnnotationCodec.update(
                written,
                SidecarAnnotations(rating = 0, label = "", caption = "", flag = SidecarFlag.NONE),
                "2026-11-05T00:00:00Z",
            )!!,
        )!!
        assertEquals(0, cleared.rating)
        assertNull(cleared.label)
        assertNull(cleared.caption)
        assertNull(cleared.flag)
        assertEquals("Colosseum", cleared.title)
    }

    @Test
    fun `a byte order mark in front of the packet is tolerated`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            SidecarFixtures.digikam.toByteArray(Charsets.UTF_8)
        assertEquals(4, XmpAnnotationCodec.readBytes(bytes)!!.rating)
    }

    @Test
    fun `unparseable existing bytes are never overwritten`() {
        assertNull(XmpAnnotationCodec.readBytes("not xml <<".toByteArray()))
        assertNull(XmpAnnotationCodec.update("not xml <<".toByteArray(), full, stamp))
    }

    // ---- foreign content ----

    private val owned = setOf(
        XmpPacket.XMP_NS to "Rating", XmpPacket.XMP_NS to "Label", XmpPacket.XMP_NS to "MetadataDate",
        XmAnn.FOTOZ to "Flag", XmpPacket.DC_NS to "subject", XmAnn.LR to "hierarchicalSubject",
        XmpPacket.DC_NS to "title", XmpPacket.DC_NS to "description", XmpPacket.DC_NS to "creator",
        XmpPacket.DC_NS to "rights",
    )

    private object XmAnn {
        const val FOTOZ = XmpAnnotationCodec.FOTOZ_NS
        const val LR = XmpAnnotationCodec.LR_NS
    }

    /** Infoset of everything we do not own: element/attribute names by namespace, values, order. */
    private fun foreignInfoset(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(text.byteInputStream())
        val sb = StringBuilder()
        fun walk(e: Element, depth: Int) {
            val attrs = (0 until e.attributes.length).map { e.attributes.item(it) }
                .filter { it.namespaceURI != "http://www.w3.org/2000/xmlns/" }
                .filter { !(depth == 0 && false) }
                .filter { (it.namespaceURI ?: "") to it.localName !in owned }
                .sortedBy { "${it.namespaceURI}${it.localName}" }
            sb.append("<{${e.namespaceURI}}${e.localName}")
            attrs.forEach { sb.append(" {${it.namespaceURI}}${it.localName}=${it.nodeValue}") }
            sb.append(">")
            var c: Node? = e.firstChild
            while (c != null) {
                when {
                    c is Element -> if ((c.namespaceURI ?: "") to c.localName !in owned) walk(c, depth + 1)
                    c.nodeType == Node.TEXT_NODE -> c.nodeValue.trim().takeIf { it.isNotEmpty() }?.let { sb.append(it) }
                }
                c = c.nextSibling
            }
            sb.append("</${e.localName}>")
        }
        walk(doc.documentElement, 0)
        return sb.toString()
    }

    private fun assertForeignPreserved(fixture: String) {
        val before = fixture.toByteArray(Charsets.UTF_8)
        val updated = XmpAnnotationCodec.update(before, full, stamp)!!
        assertEquals(foreignInfoset(before), foreignInfoset(updated))
        // and our fields did land
        val back = XmpAnnotationCodec.readBytes(updated)!!
        assertEquals(4, back.rating)
        assertEquals("Colosseum", back.title)
        assertEquals(stamp, back.metadataDate)
        assertEquals(SidecarFlag.PICK, back.flag)
    }

    @Test
    fun `Lightroom develop settings and history come back untouched`() {
        assertForeignPreserved(SidecarFixtures.lightroom)
        val updated = XmpAnnotationCodec.update(SidecarFixtures.lightroom.toByteArray(), full, stamp)!!.toString(Charsets.UTF_8)
        assertTrue(updated.contains("crs:Exposure2012=\"+0.45\""))
        assertTrue(updated.contains("stEvt:softwareAgent=\"Adobe Lightroom Classic 13.0\""))
    }

    @Test
    fun `darktable history params come back untouched`() {
        assertForeignPreserved(SidecarFixtures.darktable)
        val updated = XmpAnnotationCodec.update(SidecarFixtures.darktable.toByteArray(), full, stamp)!!.toString(Charsets.UTF_8)
        assertTrue(updated.contains("darktable:params=\"gz12eJxjYGBgkGAAgRNODFCQ\""))
    }

    @Test
    fun `digiKam tags and labels come back untouched`() {
        assertForeignPreserved(SidecarFixtures.digikam)
        val updated = XmpAnnotationCodec.update(SidecarFixtures.digikam.toByteArray(), full, stamp)!!.toString(Charsets.UTF_8)
        assertTrue(updated.contains("digiKam:ColorLabel=\"2\""))
        assertTrue(updated.contains("Nature/Sunset"))
    }

    @Test
    fun `foreign keywords and other language titles survive an edit`() {
        val updated = XmpAnnotationCodec.update(
            SidecarFixtures.lightroom.toByteArray(), SidecarAnnotations(keywords = setOf("Rome"), title = "Il Duomo"), stamp,
        )!!
        val packet = XmpPacket.parse(updated.toString(Charsets.UTF_8))!!
        assertTrue(packet.bag(XmpPacket.DC_NS, "subject").containsAll(listOf("Travel", "Italy", "Duomo", "Rome")))
        assertEquals("Il Duomo", packet.langAlt(XmpPacket.DC_NS, "title"))
        assertTrue(updated.toString(Charsets.UTF_8).contains(">Dom<"))
        assertEquals(listOf("Ada Photographer"), packet.seq(XmpPacket.DC_NS, "creator"))
    }

    @Test
    fun `only an explicitly removed keyword is deleted`() {
        val updated = XmpAnnotationCodec.update(
            SidecarFixtures.digikam.toByteArray(), SidecarAnnotations(), stamp, removedKeywords = setOf("Sea"),
        )!!
        val packet = XmpPacket.parse(updated.toString(Charsets.UTF_8))!!
        assertEquals(listOf("Sunset"), packet.bag(XmpPacket.DC_NS, "subject"))
    }

    // ---- merge ----

    @Test
    fun `newest metadata date wins scalars and keywords are a union`() {
        val local = SidecarAnnotations(rating = 5, keywords = setOf("a"), metadataDate = "2024-01-02T00:00:00Z")
        val remote = SidecarAnnotations(rating = 2, label = "Red", keywords = setOf("b"), metadataDate = "2024-01-01T23:00:00+00:00")
        val m = SidecarAnnotations.merge(local, remote)
        assertEquals(5, m.rating)
        assertEquals("Red", m.label) // loser fills what the winner has no opinion on
        assertEquals(setOf("a", "b"), m.keywords)
        val m2 = SidecarAnnotations.merge(remote.copy(metadataDate = "2025-01-01T00:00:00Z"), local)
        assertEquals(2, m2.rating)
    }

    @Test
    fun `offsets are compared as instants and a missing date is oldest`() {
        // 10:00+02:00 is 08:00Z: older than 09:00Z although the digits are larger
        val a = SidecarAnnotations(rating = 1, metadataDate = "2024-05-01T10:00:00+02:00")
        val b = SidecarAnnotations(rating = 2, metadataDate = "2024-05-01T09:00:00Z")
        assertEquals(2, SidecarAnnotations.merge(a, b).rating)
        assertEquals(1, SidecarAnnotations.merge(SidecarAnnotations(rating = 1), SidecarAnnotations(rating = 2)).rating)
        assertEquals(2, SidecarAnnotations.merge(SidecarAnnotations(rating = 1), b).rating)
        assertNull(SidecarAnnotations.epochOf("garbage"))
    }

    // ---- store: naming and files ----

    private fun original(name: String): File = File(tmp.newFolder(), name).apply { writeBytes(byteArrayOf(1)) }

    @Test
    fun `reads whichever style exists and writes the configured one`() {
        val raw = original("IMG_0420.CR3")
        File(raw.parentFile, "IMG_0420.xmp").writeText(SidecarFixtures.lightroom)
        val read = XmpAnnotationCodec.read(XmpSidecarStore.read(raw)!!)
        assertEquals(3, read.rating)

        assertTrue(XmpSidecarStore.update(raw, SidecarAnnotations(rating = 5), SidecarStyle.DARKTABLE, stamp))
        val written = File(raw.parentFile, "IMG_0420.CR3.xmp")
        assertTrue(written.isFile)
        // the Lightroom file is left alone, its foreign content was carried into the new file
        assertTrue(File(raw.parentFile, "IMG_0420.xmp").readText().contains("xmp:Rating=\"3\""))
        assertTrue(written.readText().contains("crs:Exposure2012"))
        assertEquals(5, XmpAnnotationCodec.read(XmpSidecarStore.read(raw, SidecarStyle.DARKTABLE)!!).rating)
        assertFalse(File(raw.parentFile, "IMG_0420.CR3.xmp.tmp").exists())
    }

    @Test
    fun `when both styles exist the configured style wins the read`() {
        val raw = original("IMG_1.CR3")
        File(raw.parentFile, "IMG_1.xmp").writeText(SidecarFixtures.lightroom) // rating 3
        File(raw.parentFile, "IMG_1.CR3.xmp").writeText(SidecarFixtures.digikam) // rating 4
        assertEquals(4, XmpAnnotationCodec.read(XmpSidecarStore.read(raw, SidecarStyle.DARKTABLE)!!).rating)
        assertEquals(3, XmpAnnotationCodec.read(XmpSidecarStore.read(raw, SidecarStyle.LIGHTROOM)!!).rating)
    }

    @Test
    fun `an unparseable sidecar file is left byte for byte alone`() {
        val raw = original("IMG_2.CR3")
        val sidecar = File(raw.parentFile, "IMG_2.CR3.xmp").apply { writeText("<broken") }
        assertFalse(XmpSidecarStore.update(raw, SidecarAnnotations(rating = 1), SidecarStyle.DARKTABLE, stamp))
        assertArrayEquals("<broken".toByteArray(), sidecar.readBytes())
    }

    @Test
    fun `a fresh write round trips through a real file`() {
        val jpg = original("a.jpg")
        assertTrue(XmpSidecarStore.update(jpg, full, SidecarStyle.LIGHTROOM, stamp))
        val back = XmpAnnotationCodec.read(XmpSidecarStore.read(jpg)!!)
        assertEquals("Blue", back.label)
        assertNotNull(File(jpg.parentFile, "a.xmp").takeIf { it.isFile })
    }
}
