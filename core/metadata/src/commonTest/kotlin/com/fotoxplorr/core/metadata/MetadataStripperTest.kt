package com.fotoxplorr.core.metadata

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JPEG, PNG, WebP, GIF and BMP round trips, plus the fail-closed edge cases, all on synthetic byte
 * arrays built right here -- no platform APIs at all, just [MetadataStripper] against the exact
 * container structures its class doc describes. A *real, decodable* JPEG -- for confirming the
 * stripped output still decodes to the same dimensions, and the real-photo GPS-removal case --
 * lives in `MetadataStripperJpegTest` in `:app` instead (Robolectric NATIVE mode, `Bitmap.compress`),
 * since neither is available outside the Android app module. The JPEG tests here cover what a
 * real single-scan photo can't: multi-segment keep/drop rules and a second, progressive-style
 * scan's entropy data.
 *
 * ADR-010 (WP1.2): moved from `com.fotoxplorr.app.share` (a plain JUnit4 test there) into this
 * module's `commonTest`, so it also runs on `linuxX64`/`linuxArm64` -- it never used Android or
 * Robolectric, only `java.io.ByteArrayOutputStream` as its one JVM-only detail, now replaced by a
 * plain [ByteSink] collecting into a growable list, and `kotlin.test` in place of JUnit4.
 */
class MetadataStripperTest {

    private class CollectingSink : ByteSink {
        private val bytes = mutableListOf<Byte>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) this.bytes += bytes[i]
        }
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private fun strip(input: ByteArray): Pair<MetadataStripper.StripResult, ByteArray> {
        val output = CollectingSink()
        val result = MetadataStripper.stripBytes(input, output)
        return result to output.toByteArray()
    }

    // --- JPEG ----------------------------------------------------------------------------------

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }

    @Test
    fun `JPEG strip keeps SOI and APP0 and the ICC APP2 byte-identical -- drops Exif XMP APP13 and COM -- and stops at the first EOI`() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val app0 = segment(0xE0, "JFIF\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
        val exifGps = segment(0xE1, "Exif\u0000\u0000FAKE-EXIF-WITH-GPS".encodeToByteArray())
        val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<FAKE XMP/>".encodeToByteArray())
        val icc = segment(0xE2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0xAA.toByte(), 0xBB.toByte()))
        val photoshop = segment(0xED, "FAKE PHOTOSHOP IPTC BLOCK".encodeToByteArray())
        val comment = segment(0xFE, "a comment nobody needs to keep".encodeToByteArray())
        val dqt = segment(0xDB, ByteArray(9))
        val dht = segment(0xC4, byteArrayOf(0, 1, 2, 3))
        val sof0 = segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        val sos = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        val entropyData = byteArrayOf(0x12, 0x34, 0x56)
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val trailer = byteArrayOf(0x4D, 0x50, 0x46, 0x00) // e.g. a motion-photo trailer after EOI

        val input = soi + app0 + exifGps + xmp + icc + photoshop + comment + dqt + dht + sof0 + sos + entropyData + eoi + trailer

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.JPEG), result)
        val expected = soi + app0 + icc + dqt + dht + sof0 + sos + entropyData + eoi
        assertContentEquals(expected, output)
    }

    @Test
    fun `JPEG entropy-data scanning copies FF00 stuffing and restart markers through a second progressive-style scan`() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val sof2 = segment(0xC2, byteArrayOf(8, 0, 4, 0, 4, 1, 1, 0x11, 0))
        val dht1 = segment(0xC4, byteArrayOf(0, 1, 2, 3))
        val sos1 = segment(0xDA, byteArrayOf(1, 1, 0, 0, 0, 1))
        // Contains a stuffed byte (FF 00) and a restart marker (FF D0), both of which are entropy
        // data, not the next real marker.
        val entropy1 = byteArrayOf(0x11, 0x22, 0xFF.toByte(), 0x00, 0x33, 0xFF.toByte(), 0xD0.toByte(), 0x44)
        val dht2 = segment(0xC4, byteArrayOf(1, 5, 6, 7))
        val sos2 = segment(0xDA, byteArrayOf(1, 1, 1, 0, 0, 1))
        val entropy2 = byteArrayOf(0x55, 0xFF.toByte(), 0xD1.toByte(), 0x66, 0xFF.toByte(), 0x00, 0x77)
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val input = soi + sof2 + dht1 + sos1 + entropy1 + dht2 + sos2 + entropy2 + eoi

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.JPEG), result)
        assertContentEquals(input, output) // every one of these markers is on the keep list
    }

    // --- PNG ---------------------------------------------------------------------------------

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val FAKE_CRC = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    private fun pngChunk(type: String, data: ByteArray, crc: ByteArray = FAKE_CRC): ByteArray {
        val length = data.size
        val lengthBytes = byteArrayOf(
            ((length shr 24) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
        return lengthBytes + type.encodeToByteArray() + data + crc
    }

    @Test
    fun `PNG strip keeps iCCP and acTL -- drops eXIf and iTXt -- and leaves kept chunk bytes including CRCs untouched`() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val iccp = pngChunk("iCCP", byteArrayOf(1, 2, 3, 4, 5))
        val actl = pngChunk("acTL", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))
        val exif = pngChunk("eXIf", byteArrayOf(6, 7, 8, 9))
        val itxt = pngChunk("iTXt", "GPS:51.5,-0.1".encodeToByteArray())
        val idat = pngChunk("IDAT", byteArrayOf(1, 1, 1))
        val iend = pngChunk("IEND", ByteArray(0))
        val input = PNG_MAGIC + ihdr + iccp + actl + exif + itxt + idat + iend

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.PNG), result)
        val expected = PNG_MAGIC + ihdr + iccp + actl + idat + iend
        assertContentEquals(expected, output)
    }

    @Test
    fun `PNG strip drops an unrecognized ancillary chunk but fails closed on an unrecognized critical chunk`() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val unknownAncillary = pngChunk("zzZz", byteArrayOf(1))
        val idat = pngChunk("IDAT", byteArrayOf(1))
        val iend = pngChunk("IEND", ByteArray(0))

        val (droppedResult, droppedOutput) = strip(PNG_MAGIC + ihdr + unknownAncillary + idat + iend)
        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.PNG), droppedResult)
        assertContentEquals(PNG_MAGIC + ihdr + idat + iend, droppedOutput)

        val unknownCritical = pngChunk("FOOB", byteArrayOf(1))
        val (failedResult, _) = strip(PNG_MAGIC + ihdr + unknownCritical + idat + iend)
        assertTrue(failedResult is MetadataStripper.StripResult.Unsupported)
    }

    @Test
    fun `PNG missing its IEND chunk is Unsupported rather than silently truncated`() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val idat = pngChunk("IDAT", byteArrayOf(1))
        val (result, _) = strip(PNG_MAGIC + ihdr + idat)
        assertTrue(result is MetadataStripper.StripResult.Unsupported)
    }

    // --- WebP ----------------------------------------------------------------------------------

    private fun intLe32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun webpChunk(fourCc: String, data: ByteArray): ByteArray {
        val padded = if (data.size % 2 == 1) data + byteArrayOf(0) else data
        return fourCc.encodeToByteArray() + intLe32(data.size) + padded
    }

    @Test
    fun `WebP strip drops EXIF and XMP chunks -- clears VP8X's flag bits -- and recomputes the RIFF size`() {
        // flags byte 0x0C: bit 3 (EXIF, 0x08) and bit 2 (XMP, 0x04) both set.
        val vp8x = webpChunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 7, 0, 0, 7, 0, 0))
        val exif = webpChunk("EXIF", byteArrayOf(1, 2, 3, 4, 5))
        val xmp = webpChunk("XMP ", byteArrayOf(6, 7, 8, 9))
        // Odd-length payload, to also confirm the pad byte on a KEPT chunk survives untouched.
        val image = webpChunk("VP8L", byteArrayOf(0x2F, 0, 0, 0, 0, 0xAA.toByte()))
        val body = vp8x + exif + xmp + image
        val input = "RIFF".encodeToByteArray() + intLe32(4 + body.size) + "WEBP".encodeToByteArray() + body

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.WEBP), result)
        assertEquals("RIFF", output.copyOfRange(0, 4).decodeToString())
        assertEquals("WEBP", output.copyOfRange(8, 12).decodeToString())
        val declaredSize = (output[4].toInt() and 0xFF) or ((output[5].toInt() and 0xFF) shl 8) or
            ((output[6].toInt() and 0xFF) shl 16) or ((output[7].toInt() and 0xFF) shl 24)
        assertEquals(output.size - 8, declaredSize)

        val expectedVp8x = webpChunk("VP8X", byteArrayOf(0x00, 0, 0, 0, 7, 0, 0, 7, 0, 0))
        assertContentEquals(expectedVp8x, output.copyOfRange(12, 12 + expectedVp8x.size))
        // Nothing else survives except the image chunk, directly after VP8X -- EXIF and XMP gone.
        assertContentEquals(image, output.copyOfRange(12 + expectedVp8x.size, output.size))
    }

    @Test
    fun `WebP larger than 64MB is refused rather than buffered whole`() {
        val header = "RIFF".encodeToByteArray() + intLe32(0) + "WEBP".encodeToByteArray()
        val oversized = header + ByteArray(64 * 1024 * 1024 + 1)
        val (result, _) = strip(oversized)
        assertTrue(result is MetadataStripper.StripResult.Unsupported)
    }

    // --- GIF -----------------------------------------------------------------------------------

    private fun gifSubBlock(bytes: ByteArray): ByteArray = byteArrayOf(bytes.size.toByte()) + bytes

    private fun gifAppExtension(identifier: String, extraData: ByteArray? = null): ByteArray {
        val idBytes = identifier.encodeToByteArray()
        require(idBytes.size == 11)
        val blocks = mutableListOf(gifSubBlock(idBytes))
        if (extraData != null) blocks += gifSubBlock(extraData)
        return byteArrayOf(0x21, 0xFF.toByte()) + blocks.reduce(ByteArray::plus) + byteArrayOf(0)
    }

    private fun gifCommentExtension(text: String): ByteArray =
        byteArrayOf(0x21, 0xFE.toByte()) + gifSubBlock(text.encodeToByteArray()) + byteArrayOf(0)

    private fun gifGraphicControlExtension(): ByteArray =
        byteArrayOf(0x21, 0xF9.toByte()) + gifSubBlock(byteArrayOf(0, 0, 0, 0)) + byteArrayOf(0)

    private fun gifImageBlock(): ByteArray {
        val descriptor = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x00)
        val lzwMinCodeSize = byteArrayOf(0x02)
        return descriptor + lzwMinCodeSize + gifSubBlock(byteArrayOf(0x44, 0x01)) + byteArrayOf(0)
    }

    @Test
    fun `GIF strip keeps the NETSCAPE loop extension and image data -- drops a comment and an unrelated app extension`() {
        val header = "GIF89a".encodeToByteArray()
        val lsd = byteArrayOf(1, 0, 1, 0, 0x00, 0, 0) // 1x1, no global color table
        val netscapeLoop = gifAppExtension("NETSCAPE2.0", byteArrayOf(1, 0, 0))
        val comment = gifCommentExtension("dropped")
        val gce = gifGraphicControlExtension()
        val image = gifImageBlock()
        val otherApp = gifAppExtension("SAMPLEAPP01")
        val trailer = byteArrayOf(0x3B)
        val input = header + lsd + netscapeLoop + comment + gce + image + otherApp + trailer

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.GIF), result)
        assertContentEquals(header + lsd + netscapeLoop + gce + image + trailer, output)
    }

    @Test
    fun `GIF without a trailer is Unsupported`() {
        val header = "GIF89a".encodeToByteArray()
        val lsd = byteArrayOf(1, 0, 1, 0, 0x00, 0, 0)
        val (result, _) = strip(header + lsd + gifImageBlock())
        assertTrue(result is MetadataStripper.StripResult.Unsupported)
    }

    // --- BMP -----------------------------------------------------------------------------------

    @Test
    fun `BMP has no per-photo location metadata format -- so it is copied through whole`() {
        val input = byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + ByteArray(30) { it.toByte() }
        val (result, output) = strip(input)
        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.BMP), result)
        assertContentEquals(input, output)
    }

    // --- Unrecognized / malformed ----------------------------------------------------------------

    @Test
    fun `an unrecognized format is reported as Unsupported -- not guessed at`() {
        val (result, _) = strip(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(result is MetadataStripper.StripResult.Unsupported)
    }

    @Test
    fun `a JPEG that never reaches EOI is Unsupported rather than silently handed out partial`() {
        val input = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2)
        val (result, _) = strip(input)
        assertTrue(result is MetadataStripper.StripResult.Unsupported)
    }
}
