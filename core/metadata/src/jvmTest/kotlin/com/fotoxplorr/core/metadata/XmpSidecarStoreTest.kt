package com.fotoxplorr.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [XmpSidecarStore] against real files on a real filesystem, not just [XmpPacket] in memory --
 * the same reasoning [XmpPacketTest]'s own class doc gives for testing a full serialize-then-parse
 * cycle rather than DOM calls in isolation, one level up: the actual sidecar PATH a caller ends up
 * reading or writing only comes from [SidecarNaming], never asserted against directly here.
 *
 * The fixtures below are hand-authored, not drawn from a real Lightroom/darktable corpus -- the
 * format corpus itself was deferred in WP1.8 (no licensed sample files available in this sandbox).
 * They match the field shapes [XmpPacketTest] already exercises (`xmp:Rating`, `dc:subject`), which
 * is what both real tools actually write for those two properties.
 */
class XmpSidecarStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun originalFile(name: String): File {
        val dir = tempFolder.newFolder()
        return File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `read returns an empty packet when no sidecar exists`() {
        val original = originalFile("IMG_1234.CR3")
        val packet = XmpSidecarStore.read(original)
        assertTrue(packet != null)
        assertNull(packet!!.langAlt(XmpPacket.DC_NS, "description"))
    }

    @Test
    fun `write then read round-trips through the darktable-style path`() {
        val original = originalFile("IMG_1234.CR3")
        val packet = XmpPacket.empty().apply {
            setLangAlt(XmpPacket.DC_NS, "dc", "description", "A morning at the market")
            setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 4)
            setBag(XmpPacket.DC_NS, "dc", "subject", listOf("market", "weekend"))
        }

        XmpSidecarStore.write(original, packet, SidecarStyle.DARKTABLE)

        val sidecar = File(original.parentFile, "IMG_1234.CR3.xmp")
        assertTrue("expected a darktable-style sidecar at ${sidecar.path}", sidecar.isFile)

        val reread = XmpSidecarStore.read(original)
        assertTrue(reread != null)
        assertEquals("A morning at the market", reread!!.langAlt(XmpPacket.DC_NS, "description"))
        assertEquals(4, reread.intValue(XmpPacket.XMP_NS, "Rating"))
        assertEquals(listOf("market", "weekend"), reread.bag(XmpPacket.DC_NS, "subject"))
    }

    @Test
    fun `write then read round-trips through the lightroom-style path`() {
        val original = originalFile("IMG_1234.CR3")
        val packet = XmpPacket.empty().apply {
            setSeq(XmpPacket.DC_NS, "dc", "creator", listOf("Jane Doe"))
        }

        XmpSidecarStore.write(original, packet, SidecarStyle.LIGHTROOM)

        val sidecar = File(original.parentFile, "IMG_1234.xmp")
        assertTrue("expected a lightroom-style sidecar at ${sidecar.path}", sidecar.isFile)

        val reread = XmpSidecarStore.read(original)
        assertTrue(reread != null)
        assertEquals(listOf("Jane Doe"), reread!!.seq(XmpPacket.DC_NS, "creator"))
    }

    @Test
    fun `read finds a lightroom-style sidecar even when this store would write darktable-style`() {
        val original = originalFile("IMG_1234.CR3")
        File(original.parentFile, "IMG_1234.xmp").writeText(XmpPacket.empty().serialize())

        val packet = XmpSidecarStore.read(original)
        assertTrue("a pre-existing lightroom-style sidecar should still be found", packet != null)
    }

    @Test
    fun `a sidecar that exists but fails to parse reads as null, not as empty`() {
        val original = originalFile("IMG_1234.CR3")
        File(original.parentFile, "IMG_1234.CR3.xmp").writeText("not valid xml <<<")

        assertNull(XmpSidecarStore.read(original))
    }

    @Test
    fun `write overwrites an existing sidecar at the same path`() {
        val original = originalFile("IMG_1234.CR3")
        XmpSidecarStore.write(
            original,
            XmpPacket.empty().apply { setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 2) },
            SidecarStyle.DARKTABLE,
        )
        XmpSidecarStore.write(
            original,
            XmpPacket.empty().apply { setIntValue(XmpPacket.XMP_NS, "xmp", "Rating", 5) },
            SidecarStyle.DARKTABLE,
        )

        assertEquals(5, XmpSidecarStore.read(original)!!.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    @Test
    fun `a real-shaped darktable sidecar with xpacket wrapper and multiple namespaces parses correctly`() {
        val original = originalFile("DSC_0042.NEF")
        val sidecar = File(original.parentFile, "DSC_0042.NEF.xmp")
        sidecar.writeText(
            """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmp:Rating="3">
                  <dc:subject>
                    <rdf:Bag>
                      <rdf:li>landscape</rdf:li>
                      <rdf:li>golden hour</rdf:li>
                    </rdf:Bag>
                  </dc:subject>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
            """.trimIndent(),
        )

        val packet = XmpSidecarStore.read(original)
        assertTrue(packet != null)
        assertEquals(3, packet!!.intValue(XmpPacket.XMP_NS, "Rating"))
        assertEquals(listOf("landscape", "golden hour"), packet.bag(XmpPacket.DC_NS, "subject"))
    }
}
