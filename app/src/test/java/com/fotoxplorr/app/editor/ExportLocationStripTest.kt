package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.metadata.copiedXmpValue
import com.fotoxplorr.app.metadata.xmpCarriesLocation
import com.fotoxplorr.core.metadata.XmpPacket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The proof a Save As carries no location, against the shapes the first version missed: a second
 * `rdf:Description`, repeated elements, a prefix with a digit in it, and place names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExportLocationStripTest {

    private val made = mutableListOf<File>()

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun packet(vararg descriptions: String): String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            descriptions.joinToString("") + "</rdf:RDF></x:xmpmeta>"

    private fun exported(raw: String): XmpPacket = XmpPacket.parse(copiedXmpValue(raw, keepLocation = false)!!)!!

    @Test
    fun `GPS written as an attribute on a second Description does not survive an export`() {
        val raw = packet(
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:subject><rdf:Bag><rdf:li>harbour</rdf:li></rdf:Bag></dc:subject></rdf:Description>",
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" exif:GPSLatitude=\"51,30.4N\" exif:GPSLongitude=\"0,7.6W\"/>",
        )

        val out = exported(raw)

        assertFalse(xmpCarriesLocation(out))
        assertTrue("the rest of the packet is kept", out.bag(XmpPacket.DC_NS, "subject") == listOf("harbour"))
    }

    @Test
    fun `duplicate GPS elements are all removed`() {
        val raw = packet(
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"><exif:GPSLatitude>51</exif:GPSLatitude><exif:GPSLatitude>52</exif:GPSLatitude></rdf:Description>",
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"><exif:GPSLatitude>53</exif:GPSLatitude></rdf:Description>",
        )

        assertFalse(xmpCarriesLocation(exported(raw)))
    }

    @Test
    fun `a prefix with a digit in it is matched by namespace`() {
        val raw = packet("<rdf:Description rdf:about=\"\" xmlns:ex2=\"http://ns.adobe.com/exif/1.0/\" ex2:GPSLongitude=\"0,7.6W\"/>")
        val parsed = XmpPacket.parse(raw)!!
        assertTrue("the fixture must carry a location for this to prove anything", xmpCarriesLocation(parsed))

        assertFalse(xmpCarriesLocation(exported(raw)))
    }

    @Test
    fun `place names go too`() {
        val raw = packet(
            "<rdf:Description rdf:about=\"\" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\" " +
                "photoshop:City=\"London\" photoshop:Country=\"UK\" Iptc4xmpCore:CountryCode=\"GB\"><photoshop:State>England</photoshop:State><Iptc4xmpCore:Location>Wapping</Iptc4xmpCore:Location></rdf:Description>",
        )
        assertTrue(xmpCarriesLocation(XmpPacket.parse(raw)!!))

        assertFalse(xmpCarriesLocation(exported(raw)))
    }

    @Test
    fun `an IPTC location structure holding its own coordinates goes with its container`() {
        val raw = packet(
            "<rdf:Description rdf:about=\"\" xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\">" +
                "<Iptc4xmpExt:LocationCreated><rdf:Bag><rdf:li rdf:parseType=\"Resource\"><exif:GPSLatitude>51</exif:GPSLatitude></rdf:li></rdf:Bag></Iptc4xmpExt:LocationCreated></rdf:Description>",
        )

        assertFalse(xmpCarriesLocation(exported(raw)))
    }

    @Test
    fun `an unrelated packet is not reported as carrying a location`() {
        val raw = packet("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"4\"/>")
        assertFalse(xmpCarriesLocation(XmpPacket.parse(raw)!!))
    }

    // ---- the file-level proof ----

    private fun jpegWithXmp(xmp: String): File {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        val base = out.toByteArray()
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return File.createTempFile("fx-strip", ".jpg").also {
            made += it
            it.writeBytes(base.copyOfRange(0, 2) + segment + base.copyOfRange(2, base.size))
        }
    }

    @Test
    fun `carriesLocation sees GPS in a second Description and under a digit prefix`() {
        val file = jpegWithXmp(packet("<rdf:Description rdf:about=\"\"/>", "<rdf:Description rdf:about=\"\" xmlns:ex2=\"http://ns.adobe.com/exif/1.0/\" ex2:GPSLatitude=\"51\"/>"))

        assertTrue(carriesLocation(file))
    }

    @Test
    fun `carriesLocation sees a place name`() {
        val file = jpegWithXmp(packet("<rdf:Description rdf:about=\"\" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" photoshop:City=\"London\"/>"))

        assertTrue(carriesLocation(file))
    }

    @Test
    fun `carriesLocation is false for a packet with none`() {
        val file = jpegWithXmp(packet("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"4\"/>"))

        assertFalse(carriesLocation(file))
        assertNotNull(ExifInterface(file.absolutePath).getAttributeBytes(ExifInterface.TAG_XMP))
    }
}
