package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a replace would silently lose from a PNG or WebP, which [ExifInterface] cannot carry. */
class UncarriedPngWebpMetadataTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun pngChunk(type: String, data: ByteArray) = be32(data.size) + ascii(type) + data + ByteArray(4)

    private val ihdr = pngChunk("IHDR", ByteArray(13))
    private val idat = pngChunk("IDAT", ByteArray(40))
    private val iend = pngChunk("IEND", ByteArray(0))

    private fun png(vararg chunks: ByteArray) = pngSignature + chunks.fold(ByteArray(0)) { acc, c -> acc + c }

    private fun webpChunk(type: String, data: ByteArray) =
        ascii(type) + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ascii("RIFF") + le32(body.size) + body
    }

    // ---- PNG ----

    @Test
    fun `a PNG with a tEXt prompt is found`() {
        val text = pngChunk("tEXt", ascii("parameters\u0000a cat, steps: 20"))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, text, idat, iend)))
    }

    @Test
    fun `a PNG with a zTXt or a non-XMP iTXt is found`() {
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, pngChunk("zTXt", ascii("Comment\u0000\u0000xx")), idat, iend)))
        val itxt = pngChunk("iTXt", ascii("Description\u0000\u0000\u0000\u0000\u0000a long caption"))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, itxt, idat, iend)))
    }

    @Test
    fun `the XMP iTXt alone is not a loss`() {
        val xmp = pngChunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta/>"))
        assertTrue(uncarriedMetadataIn(png(ihdr, xmp, idat, iend)).isEmpty())
    }

    @Test
    fun `a PNG colour profile alone is not a loss because the re-encode keeps the colour space`() {
        val profile = pngChunk("iCCP", ascii("sRGB\u0000\u0000") + ByteArray(12))
        assertTrue(uncarriedMetadataIn(png(ihdr, profile, idat, iend)).isEmpty())
    }

    @Test
    fun `text after the image data is found and the image data is skipped by length`() {
        val text = pngChunk("tEXt", ascii("Comment\u0000x"))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, idat, text, iend)))
    }

    @Test
    fun `tool and time stamp keywords are not a loss`() {
        val stamps = listOf(
            pngChunk("tEXt", ascii("Software\u0000ImageMagick 7.1")),
            pngChunk("tEXt", ascii("Creation Time\u0000Tue, 01 Jan 2030 00:00:00 GMT")),
            pngChunk("tEXt", ascii("date:create\u00002030-01-01T00:00:00+00:00")),
            pngChunk("tEXt", ascii("date:modify\u00002030-01-01T00:00:00+00:00")),
            pngChunk("tEXt", ascii("date:timestamp\u00002030-01-01T00:00:00+00:00")),
            pngChunk("zTXt", ascii("Software\u0000\u0000xx")),
            pngChunk("iTXt", ascii("Software\u0000\u0000\u0000\u0000\u0000GIMP")),
            pngChunk("tEXt", ascii("Thumb::URI\u0000file:///a.png")),
        )
        assertTrue(uncarriedMetadataIn(png(ihdr, *stamps.toTypedArray(), idat, iend)).isEmpty())
    }

    @Test
    fun `a stamp next to real content still counts the content`() {
        val stamp = pngChunk("tEXt", ascii("date:create\u00002030-01-01"))
        val prompt = pngChunk("tEXt", ascii("parameters\u0000a cat"))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, stamp, prompt, idat, iend)))
    }

    @Test
    fun `keywords with real content count including ImageMagick raw profile dumps and unknown ones`() {
        listOf("parameters", "prompt", "Comment", "Description", "Author", "Copyright", "Raw profile type exif", "SomethingNew").forEach { keyword ->
            val chunk = pngChunk("tEXt", ascii("$keyword\u0000value"))
            assertEquals(keyword, setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, chunk, idat, iend)))
        }
    }

    @Test
    fun `a text chunk whose keyword is not terminated in the window counts`() {
        val unterminated = pngChunk("tEXt", ascii("Software"))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(png(ihdr, unterminated, idat, iend)))
    }

    @Test
    fun `the keyword rule ignores case for stamps and treats a missing keyword as content`() {
        assertTrue(isIgnorablePngTextKeyword("software"))
        assertTrue(isIgnorablePngTextKeyword("DATE:create"))
        assertTrue(!isIgnorablePngTextKeyword(null))
        assertTrue(!isIgnorablePngTextKeyword("Softwarex"))
    }

    // ---- WebP ----

    @Test
    fun `a plain WebP with extended header image data exif xmp and profile is clean`() {
        val file = webp(
            webpChunk("VP8X", ByteArray(10)),
            webpChunk("ICCP", ByteArray(7)),
            webpChunk("ALPH", ByteArray(5)),
            webpChunk("VP8 ", ByteArray(31)),
            webpChunk("EXIF", ByteArray(12)),
            webpChunk("XMP ", ascii("<x:xmpmeta/>")),
        )
        assertTrue(uncarriedMetadataIn(file).isEmpty())
        assertTrue(uncarriedMetadataIn(webp(webpChunk("VP8L", ByteArray(9)))).isEmpty())
    }

    @Test
    fun `a WebP with any other chunk is found`() {
        val file = webp(webpChunk("VP8X", ByteArray(10)), webpChunk("VP8 ", ByteArray(31)), webpChunk("ZZZZ", ByteArray(6)))
        assertEquals(setOf(UncarriedMetadata.WEBP_CHUNK), uncarriedMetadataIn(file))
    }

    @Test
    fun `an odd sized WebP chunk is stepped over by its padding`() {
        val file = webp(webpChunk("VP8 ", ByteArray(31)), webpChunk("EXIF", ByteArray(5)), webpChunk("XMP ", ByteArray(3)))
        assertTrue(uncarriedMetadataIn(file).isEmpty())
    }

    @Test
    fun `a WebP chunk size that runs off the end ends the walk`() {
        val hostile = ascii("RIFF") + le32(100) + ascii("WEBP") + ascii("VP8 ") + le32(Int.MAX_VALUE) + ByteArray(8)
        assertTrue(uncarriedMetadataIn(hostile).isEmpty())
    }

    @Test
    fun `a file that is neither JPEG PNG nor WebP answers nothing`() {
        assertTrue(uncarriedMetadataIn(ascii("GIF89a....")).isEmpty())
    }
}
