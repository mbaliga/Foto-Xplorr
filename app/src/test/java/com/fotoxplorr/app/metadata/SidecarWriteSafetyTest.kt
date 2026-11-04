package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The sidecar review findings on the app side: RAW+JPEG naming, safe SAF overwrite, case and provider renames. */
class SidecarWriteSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val on = SidecarPolicy(writeEnabled = true)
    private val jpeg = SidecarPolicy.Source(isRaw = false, embedWritable = true)
    private val raw = SidecarPolicy.Source(isRaw = true, embedWritable = false)
    private val stamp = "2026-11-04T12:00:00Z"
    private fun service() = DefaultSidecarService({ on }, { stamp })

    private val rawSidecar = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
          xmlns:dc="http://purl.org/dc/elements/1.1/" xmp:Rating="5" crs:Exposure2012="+0.45">
          <dc:subject><rdf:Bag><rdf:li>RawOnly</rdf:li></rdf:Bag></dc:subject></rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test
    fun `a JPEG beside its RAW neither reads nor inherits the RAW's Lightroom sidecar`() {
        val dir = InMemorySidecarDirectory().also {
            it.others += setOf("IMG_1.CR2", "IMG_1.JPG")
            it.files["IMG_1.xmp"] = rawSidecar.toByteArray()
        }
        val jpg = SidecarAsset("IMG_1.JPG", jpeg, SidecarParent.Directory(dir))
        assertTrue(service().read(jpg) is SidecarReadResult.NotFound)
        assertEquals(
            SidecarLocation.Writable("mem://folder", "IMG_1.JPG.xmp", false),
            SidecarLocator.locate("IMG_1.JPG", SidecarParent.Directory(dir), on.style),
        )
        assertTrue(service().write(jpg, SidecarAnnotations(label = "Red"), true) is SidecarWriteResult.Written)
        val written = dir.files["IMG_1.JPG.xmp"]!!.toString(Charsets.UTF_8)
        assertFalse(written.contains("crs:") || written.contains("RawOnly") || written.contains("Rating"))
        // the RAW itself still finds its own sidecar
        val cr2 = SidecarAsset("IMG_1.CR2", raw, SidecarParent.Directory(dir))
        assertEquals(5, (service().read(cr2) as SidecarReadResult.Found).annotations.rating)
    }

    @Test
    fun `a failed SAF overwrite restores the foreign sidecar and never says Written`() {
        val old = rawSidecar.toByteArray()
        val docs = InMemoryDocuments().also { it.docs["IMG.CR3.xmp"] = old }
        docs.failWriteOf = { it == "IMG.CR3.xmp" && docs.docs["IMG.CR3.xmp"]!!.contentEquals(old) } // the new bytes fail once
        assertFalse(SafeDocumentWrite.overwrite(docs, "IMG.CR3.xmp", "IMG.CR3.xmp", "new".toByteArray()))
        assertArrayEquals(old, docs.docs["IMG.CR3.xmp"])
        assertEquals(setOf("IMG.CR3.xmp"), docs.docs.keys) // backup removed once the original is known good

        // the restore fails too: the old bytes stay on disk in the backup
        val stuck = InMemoryDocuments().also { it.docs["IMG.CR3.xmp"] = old }
        stuck.failWriteOf = { it == "IMG.CR3.xmp" }
        assertFalse(SafeDocumentWrite.overwrite(stuck, "IMG.CR3.xmp", "IMG.CR3.xmp", "new".toByteArray()))
        assertArrayEquals(old, stuck.docs["IMG.CR3.xmp.bak"])

        // no backup possible: nothing is truncated at all
        val noRoom = InMemoryDocuments().also { it.docs["IMG.CR3.xmp"] = old; it.failCreate = true }
        assertFalse(SafeDocumentWrite.overwrite(noRoom, "IMG.CR3.xmp", "IMG.CR3.xmp", "new".toByteArray()))
        assertArrayEquals(old, noRoom.docs["IMG.CR3.xmp"])

        // the good path: new bytes, verified, no backup left
        val good = InMemoryDocuments().also { it.docs["IMG.CR3.xmp"] = old }
        assertTrue(SafeDocumentWrite.overwrite(good, "IMG.CR3.xmp", "IMG.CR3.xmp", "new".toByteArray()))
        assertEquals("new", good.docs["IMG.CR3.xmp"]!!.toString(Charsets.UTF_8))
        assertEquals(setOf("IMG.CR3.xmp"), good.docs.keys)

        // and the service reports Failed when a directory write fails over an existing sidecar
        val dir = InMemorySidecarDirectory(failWrites = true).also { it.files["a.jpg.xmp"] = old }
        val r = service().write(SidecarAsset("a.jpg", jpeg, SidecarParent.Directory(dir)), SidecarAnnotations(rating = 1), true)
        assertTrue(r is SidecarWriteResult.Failed)
        assertArrayEquals(old, dir.files["a.jpg.xmp"])
    }

    @Test
    fun `find ignores case and a provider renamed create is refused`() {
        assertEquals(1, SidecarNameMatch.indexOf(listOf("x", "IMG_1.CR3.XMP"), "IMG_1.CR3.xmp"))
        assertEquals(2, SidecarNameMatch.indexOf(listOf("x", "IMG_1.CR3.XMP", "IMG_1.CR3.xmp"), "IMG_1.CR3.xmp"))
        assertNull(SidecarNameMatch.indexOf(listOf("x"), "IMG_1.CR3.xmp"))

        // an existing upper-case sidecar is merged into and keeps its name; no second file appears
        val dir = InMemorySidecarDirectory().also { it.files["IMG_1.CR3.XMP"] = rawSidecar.toByteArray() }
        val w = service().write(SidecarAsset("IMG_1.CR3", raw, SidecarParent.Directory(dir)), SidecarAnnotations(label = "Red"), true)
        assertEquals("IMG_1.CR3.XMP", (w as SidecarWriteResult.Written).location.fileName)
        assertEquals(listOf("IMG_1.CR3.XMP"), dir.files.keys.toList())
        assertTrue(dir.files["IMG_1.CR3.XMP"]!!.toString(Charsets.UTF_8).contains("crs:Exposure2012"))

        // the same on a real folder
        val folder = tmp.newFolder()
        java.io.File(folder, "P.JPG.XMP").writeText(rawSidecar)
        val fileDir = FileSidecarDirectory(folder)
        assertEquals("P.JPG.XMP", fileDir.find("P.JPG.xmp")!!.fileName)

        // SAF create on case-insensitive storage: the provider answers "x (1).xmp", which is not ours
        val docs = InMemoryDocuments().also { it.docs["IMG_1.CR3.XMP"] = byteArrayOf(1) }
        assertFalse(SafeDocumentWrite.create(docs, "IMG_1.CR3.xmp", "new".toByteArray()))
        assertEquals(setOf("IMG_1.CR3.XMP"), docs.docs.keys)
        assertTrue(SafeDocumentWrite.create(InMemoryDocuments(), "IMG_1.CR3.xmp", "new".toByteArray()))
    }
}
