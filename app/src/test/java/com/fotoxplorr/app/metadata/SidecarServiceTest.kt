package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import com.fotoxplorr.core.metadata.SidecarPolicy
import com.fotoxplorr.core.metadata.SidecarStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SidecarServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val on = SidecarPolicy(writeEnabled = true)
    private val jpeg = SidecarPolicy.Source(isRaw = false, embedWritable = true)
    private val raw = SidecarPolicy.Source(isRaw = true, embedWritable = false)
    private val stamp = "2026-11-04T12:00:00Z"

    private fun service(policy: SidecarPolicy) = DefaultSidecarService({ policy }, { stamp })
    private fun asset(name: String, dir: SidecarDirectory?, source: SidecarPolicy.Source = jpeg) =
        SidecarAsset(name, source, dir?.let { SidecarParent.Directory(it) } ?: SidecarParent.NoGrant)

    private val lightroomish = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
          xmlns:dc="http://purl.org/dc/elements/1.1/" xmp:Rating="2" crs:Exposure2012="+0.45">
          <dc:subject><rdf:Bag><rdf:li>Travel</rdf:li></rdf:Bag></dc:subject></rdf:Description></rdf:RDF></x:xmpmeta>"""

    // ---- locator ----

    @Test
    fun `no grant is NeedsFolderGrant and never a fake success`() {
        assertEquals(SidecarLocation.NeedsFolderGrant, SidecarLocator.locate("a.jpg", SidecarParent.NoGrant, SidecarStyle.DARKTABLE))
        assertEquals(
            SidecarLocation.Unsupported("cloud"),
            SidecarLocator.locate("a.jpg", SidecarParent.Unsupported("cloud"), SidecarStyle.DARKTABLE),
        )
    }

    @Test
    fun `a folder we cannot create in is NeedsFolderGrant, an existing read only sidecar is ReadOnly`() {
        val ro = InMemorySidecarDirectory(canCreate = false)
        assertEquals(SidecarLocation.NeedsFolderGrant, SidecarLocator.locate("a.jpg", SidecarParent.Directory(ro), SidecarStyle.DARKTABLE))
        ro.files["a.jpg.xmp"] = lightroomish.toByteArray()
        val roSidecar = InMemorySidecarDirectory(canCreate = false, readOnlyNames = setOf("a.jpg.xmp")).also { it.files["a.jpg.xmp"] = byteArrayOf(1) }
        assertTrue(SidecarLocator.locate("a.jpg", SidecarParent.Directory(roSidecar), SidecarStyle.DARKTABLE) is SidecarLocation.ReadOnly)
    }

    @Test
    fun `locator finds either style and names the configured one for a new file`() {
        val dir = InMemorySidecarDirectory().also { it.files["IMG.xmp"] = byteArrayOf(1) }
        val found = SidecarLocator.locate("IMG.CR3", SidecarParent.Directory(dir), SidecarStyle.DARKTABLE)
        assertEquals(SidecarLocation.Writable("mem://folder/IMG.xmp", "IMG.xmp", true), found)
        val empty = InMemorySidecarDirectory()
        assertEquals(
            SidecarLocation.Writable("mem://folder", "IMG.CR3.xmp", false),
            SidecarLocator.locate("IMG.CR3", SidecarParent.Directory(empty), SidecarStyle.DARKTABLE),
        )
    }

    // ---- service ----

    @Test
    fun `default policy writes nothing`() {
        val dir = InMemorySidecarDirectory()
        val r = service(SidecarPolicy()).write(asset("a.jpg", dir), SidecarAnnotations(rating = 5), true)
        assertTrue(r is SidecarWriteResult.Skipped)
        assertTrue(dir.files.isEmpty())
    }

    @Test
    fun `an index style call without an explicit action writes nothing`() {
        val dir = InMemorySidecarDirectory()
        val r = service(on).write(asset("a.jpg", dir), SidecarAnnotations(rating = 5), explicitUserAction = false)
        assertTrue(r is SidecarWriteResult.Skipped)
        assertTrue(dir.files.isEmpty())
    }

    @Test
    fun `write then read round trips and a RAW gets a sidecar beside it`() {
        val dir = InMemorySidecarDirectory()
        val a = asset("IMG_1.CR3", dir, raw)
        val ann = SidecarAnnotations(rating = 4, flag = SidecarFlag.PICK, keywords = setOf("x"), caption = "c")
        val w = service(on).write(a, ann, true)
        assertTrue(w is SidecarWriteResult.Written)
        assertEquals(listOf("IMG_1.CR3.xmp"), dir.files.keys.toList())
        val r = service(on).read(a) as SidecarReadResult.Found
        assertEquals(4, r.annotations.rating)
        assertEquals(SidecarFlag.PICK, r.annotations.flag)
        assertEquals(stamp, r.annotations.metadataDate)
    }

    @Test
    fun `embed is requested for a writable format only when allowed and is not done here`() {
        val dir = InMemorySidecarDirectory()
        val s = service(on.copy(embedWhenWritable = true))
        assertEquals(SidecarWriteResult.EmbedInFileRequested, s.write(asset("a.jpg", dir), SidecarAnnotations(rating = 1), true))
        assertTrue(dir.files.isEmpty())
        assertTrue(s.write(asset("a.cr3", dir, raw), SidecarAnnotations(rating = 1), true) is SidecarWriteResult.Written)
    }

    @Test
    fun `writing in the configured style carries a Lightroom sidecar's foreign content over`() {
        val dir = InMemorySidecarDirectory().also { it.files["IMG.xmp"] = lightroomish.toByteArray() }
        val untouched = dir.files["IMG.xmp"]!!.copyOf()
        val r = service(on).write(asset("IMG.CR3", dir, raw), SidecarAnnotations(keywords = setOf("Italy")), true)
        assertTrue(r is SidecarWriteResult.Written)
        val written = dir.files["IMG.CR3.xmp"]!!.toString(Charsets.UTF_8)
        assertTrue(written.contains("crs:Exposure2012=\"+0.45\""))
        assertTrue(written.contains("Travel") && written.contains("Italy"))
        assertArrayEquals(untouched, dir.files["IMG.xmp"])
    }

    @Test
    fun `an unparseable sidecar is reported and left alone`() {
        val dir = InMemorySidecarDirectory().also { it.files["a.jpg.xmp"] = "<<".toByteArray() }
        val a = asset("a.jpg", dir)
        assertTrue(service(on).read(a) is SidecarReadResult.Unparseable)
        assertEquals(SidecarWriteResult.ExistingSidecarUnparseable, service(on).write(a, SidecarAnnotations(rating = 1), true))
        assertEquals("<<", dir.files["a.jpg.xmp"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `no grant read and write are honest`() {
        val a = asset("a.jpg", null)
        assertTrue(service(on).read(a) is SidecarReadResult.NoAccess)
        assertEquals(SidecarWriteResult.NeedsFolderGrant, service(on).write(a, SidecarAnnotations(rating = 1), true))
    }

    @Test
    fun `a failing write is Failed not Written`() {
        val dir = InMemorySidecarDirectory(failWrites = true)
        assertTrue(service(on).write(asset("a.jpg", dir), SidecarAnnotations(rating = 1), true) is SidecarWriteResult.Failed)
    }

    @Test
    fun `plain file directory round trips on a real filesystem`() {
        val folder = tmp.newFolder()
        val dir = FileSidecarDirectory(folder)
        val a = asset("p.jpg", dir)
        assertTrue(service(on).write(a, SidecarAnnotations(label = "Red"), true) is SidecarWriteResult.Written)
        assertTrue(java.io.File(folder, "p.jpg.xmp").isFile)
        assertFalse(java.io.File(folder, "p.jpg.xmp.tmp").exists())
        assertEquals("Red", (service(on).read(a) as SidecarReadResult.Found).annotations.label)
    }

    // ---- pure helpers ----

    @Test
    fun `index reader is a pure function over bytes`() {
        assertEquals(2, SidecarIndexReader.annotationsFrom(lightroomish.toByteArray())!!.rating)
        assertNull(SidecarIndexReader.annotationsFrom("nope".toByteArray()))
    }

    @Test
    fun `saf parent id is derived from volume and relative path only inside the granted tree`() {
        assertEquals("primary:Pictures/Fotoz/trip", SafParentResolver.parentDocumentId("primary:Pictures/Fotoz", "external_primary", "Pictures/Fotoz/trip/"))
        assertEquals("primary:Pictures/Fotoz", SafParentResolver.parentDocumentId("primary:Pictures/Fotoz", "external_primary", "Pictures/Fotoz/"))
        assertNull(SafParentResolver.parentDocumentId("primary:Pictures/Fotoz", "external_primary", "Pictures/FotozOther/"))
        assertNull(SafParentResolver.parentDocumentId("primary:Pictures/Fotoz", "external_primary", "DCIM/"))
        assertNull(SafParentResolver.parentDocumentId("primary:Pictures", "1A2B-3C4D", "Pictures/"))
        assertEquals("1A2B-3C4D:DCIM/100", SafParentResolver.parentDocumentId("1A2B-3C4D:", "1A2B-3C4D", "DCIM/100/"))
        assertNull(SafParentResolver.parentDocumentId("primary:Pictures", "external_primary", "Pictures/../DCIM/"))
    }

    @Test
    fun `fake service records calls`() {
        val fake = FakeSidecarService()
        fake.write(asset("a.jpg", null), SidecarAnnotations(rating = 3), true)
        assertEquals(1, fake.writes.size)
        assertTrue(fake.read(asset("a.jpg", null)) is SidecarReadResult.NotFound)
    }
}
