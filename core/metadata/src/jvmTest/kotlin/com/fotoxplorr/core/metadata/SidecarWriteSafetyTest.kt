package com.fotoxplorr.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The sidecar review findings: user edits win, no foreign keyword rewrite, XML-safe text, RAW+JPEG naming. */
class SidecarWriteSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stamp = "2026-11-04T12:00:00Z"

    private fun sidecar(description: String, body: String = "") =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/"
          xmlns:lr="http://ns.adobe.com/lightroom/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" $description>$body
        </rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test
    fun `an explicit edit beats a sidecar whose metadata date sorts after now`() {
        // offset-less local time from a desktop ahead of UTC: read as UTC it is "in the future"
        val future = sidecar(
            """xmp:Rating="5" xmp:Label="Red" xmp:MetadataDate="2026-11-04T18:00:00"""",
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Old</rdf:li></rdf:Alt></dc:title>",
        )
        val updated = XmpAnnotationCodec.update(
            future.toByteArray(), SidecarAnnotations(rating = 2, title = "New", flag = SidecarFlag.PICK), stamp,
        )!!
        val back = XmpAnnotationCodec.readBytes(updated)!!
        assertEquals(2, back.rating)
        assertEquals("New", back.title)
        assertEquals(SidecarFlag.PICK, back.flag)
        assertEquals("Red", back.label) // untouched fields stay as the file had them
        assertEquals(stamp, back.metadataDate)
    }

    @Test
    fun `a rating only write leaves a foreign flat keyword list alone`() {
        // Lightroom with "exclude on export" on the parent: the path is there, the parent is not a keyword
        val lightroom = sidecar(
            """xmp:Rating="1"""",
            """<dc:subject><rdf:Bag><rdf:li>France</rdf:li></rdf:Bag></dc:subject>
               <lr:hierarchicalSubject><rdf:Bag><rdf:li>Places|France</rdf:li></rdf:Bag></lr:hierarchicalSubject>""",
        )
        val updated = XmpAnnotationCodec.update(lightroom.toByteArray(), SidecarAnnotations(rating = 3), stamp)!!
        val packet = XmpPacket.parse(updated.toString(Charsets.UTF_8))!!
        assertEquals(listOf("France"), packet.bag(XmpPacket.DC_NS, "subject"))
        assertEquals(listOf("Places|France"), packet.bag(XmpAnnotationCodec.LR_NS, "hierarchicalSubject"))
        assertEquals(3, packet.intValue(XmpPacket.XMP_NS, "Rating"))
        // a path the user adds in this call is still expanded, as Lightroom does
        val added = XmpAnnotationCodec.readBytes(
            XmpAnnotationCodec.update(updated, SidecarAnnotations(keywordPaths = setOf("Trips|Lyon")), stamp)!!,
        )!!
        assertEquals(setOf("France", "Trips", "Lyon"), added.keywords)
    }

    @Test
    fun `control characters and lone surrogates in user text never make the packet unreadable`() {
        val nasty = "Sun\u0001set\u000B at \uD800the 😀 pier\u0000"
        val bytes = XmpAnnotationCodec.update(
            null,
            SidecarAnnotations(caption = nasty, title = nasty, label = "Re\u0007d", keywords = setOf("bea\u001Fch"), creators = listOf("A\u0002n")),
            stamp,
        )!!
        val back = XmpAnnotationCodec.readBytes(bytes)
        assertNotNull("the written sidecar must parse again", back)
        assertEquals("Sunset at the 😀 pier", back!!.caption)
        assertEquals("Red", back.label)
        assertEquals(setOf("beach"), back.keywords)
        assertEquals(listOf("An"), back.creators)
        // and a second write over it still works
        assertNotNull(XmpAnnotationCodec.update(bytes, SidecarAnnotations(rating = 1), stamp))
        assertEquals("tab\tnew\nline", XmpPacket.xmlSafe("tab\tnew\nline"))
    }

    @Test
    fun `a JPEG never takes its RAW partner's extension-less sidecar`() {
        val folder = tmp.newFolder()
        File(folder, "IMG_1.CR2").writeBytes(byteArrayOf(1))
        val jpg = File(folder, "IMG_1.JPG").apply { writeBytes(byteArrayOf(1)) }
        val rawSidecar = File(folder, "IMG_1.xmp").apply {
            writeText(sidecar("""xmp:Rating="5" crs:Exposure2012="+0.45""""))
        }
        assertEquals(
            listOf("IMG_1.JPG.xmp"),
            SidecarNaming.candidateSidecarFileNames("IMG_1.JPG", SidecarStyle.DARKTABLE, folder.list()!!.toList()),
        )
        assertEquals(null, XmpAnnotationCodec.read(XmpSidecarStore.read(jpg)!!).rating)
        assertTrue(XmpSidecarStore.update(jpg, SidecarAnnotations(label = "Blue"), SidecarStyle.DARKTABLE, stamp))
        val written = File(folder, "IMG_1.JPG.xmp").readText()
        assertTrue(!written.contains("crs:") && !written.contains("Rating"))
        assertTrue(rawSidecar.readText().contains("xmp:Rating=\"5\""))
        // the RAW, which Lightroom wrote that file for, still reads it
        assertEquals(5, XmpAnnotationCodec.read(XmpSidecarStore.read(File(folder, "IMG_1.CR2"))!!).rating)

        // without a partner the Lightroom name is still found, and a user who picked Lightroom keeps it
        assertEquals(
            listOf("A.JPG.xmp", "A.xmp"),
            SidecarNaming.candidateSidecarFileNames("A.JPG", SidecarStyle.DARKTABLE, listOf("A.JPG", "A.xmp", "B.CR2")),
        )
        assertEquals(
            listOf("IMG_1.xmp", "IMG_1.JPG.xmp"),
            SidecarNaming.candidateSidecarFileNames("IMG_1.JPG", SidecarStyle.LIGHTROOM, listOf("IMG_1.cr2")),
        )
    }

    @Test
    fun `a keyword added with a control character can be removed again by the same string`() {
        val raw = "bea\u001Fch"
        val added = XmpAnnotationCodec.update(
            null, SidecarAnnotations(keywords = setOf(raw, "keep"), keywordPaths = setOf("Places|bea\u001Fch")), stamp,
        )!!
        assertEquals(setOf("beach", "keep", "Places"), XmpAnnotationCodec.readBytes(added)!!.keywords)
        val removed = XmpAnnotationCodec.update(
            added, SidecarAnnotations(), stamp, removedKeywords = setOf(raw, " Places "), removedPaths = setOf("Places|bea\u001Fch"),
        )!!
        val back = XmpAnnotationCodec.readBytes(removed)!!
        assertEquals(setOf("keep"), back.keywords)
        assertTrue(back.keywordPaths.isEmpty())
    }

    @Test
    fun `a folder that cannot be listed offers only the configured style's own name`() {
        assertEquals(listOf("IMG_1.JPG.xmp"), XmpSidecarStore.candidateNames("IMG_1.JPG", null, null))
        assertEquals(listOf("IMG_1.JPG.xmp"), XmpSidecarStore.candidateNames("IMG_1.JPG", SidecarStyle.DARKTABLE, null))
        assertEquals(listOf("IMG_1.CR2.xmp"), XmpSidecarStore.candidateNames("IMG_1.CR2", SidecarStyle.DARKTABLE, null))
        // listed and alone, the JPEG still finds a Lightroom-style name
        assertTrue("IMG_1.xmp" in XmpSidecarStore.candidateNames("IMG_1.JPG", SidecarStyle.DARKTABLE, listOf("IMG_1.JPG")))
        assertTrue("IMG_1.xmp" !in XmpSidecarStore.candidateNames("IMG_1.JPG", SidecarStyle.DARKTABLE, listOf("IMG_1.JPG", "IMG_1.CR2")))
    }
}
