package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The whole-file walk for PNG and WebP: text or extra chunks that sit after more image data than
 * the 256 KiB head window holds are still seen, without reading the image data.
 */
class UncarriedStreamWalkTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun pngChunk(type: String, data: ByteArray) = be32(data.size) + ascii(type) + data + ByteArray(4)
    private fun png(vararg chunks: ByteArray) = pngSignature + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    private fun webpChunk(type: String, data: ByteArray) =
        ascii(type) + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ascii("RIFF") + le32(body.size) + body
    }

    private val ihdr = pngChunk("IHDR", ByteArray(13))
    private val iend = pngChunk("IEND", ByteArray(0))
    private val bigIdat = pngChunk("IDAT", ByteArray(700_000))
    private val prompt = pngChunk("tEXt", ascii("parameters\u0000a cat, steps: 20"))

    /** Counts the bytes actually read (a skip is not a read). */
    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0L
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
    }

    /** A stream whose skip does nothing, like a provider stream that cannot seek. */
    private class NoSkipStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun skip(n: Long): Long = 0L
    }

    @Test
    fun `a prompt after more image data than the head window holds is missed by the head and seen by the walk`() {
        val file = png(ihdr, bigIdat, prompt, iend)
        val head = file.copyOf(UNCARRIED_SCAN_BYTES)

        assertTrue("the old window cannot see it", uncarriedMetadataIn(head).isEmpty())
        assertTrue(metadataNeedsFullWalk(head))
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(ByteArrayInputStream(file)))
    }

    @Test
    fun `the walk skips the image data instead of reading it`() {
        val stream = CountingStream(png(ihdr, bigIdat, prompt, iend))

        uncarriedMetadataIn(stream)

        assertTrue("read ${stream.bytesRead} bytes", stream.bytesRead < 1_000)
    }

    @Test
    fun `a stream that cannot skip is read through and gives the same answer`() {
        assertEquals(
            setOf(UncarriedMetadata.PNG_TEXT),
            uncarriedMetadataIn(NoSkipStream(png(ihdr, bigIdat, prompt, iend))),
        )
    }

    @Test
    fun `a large PNG with only boilerplate after the data stays clean`() {
        val stamps = listOf(
            pngChunk("tEXt", ascii("Software\u0000GIMP")),
            pngChunk("tEXt", ascii("date:create\u00002030-01-01T00:00:00+00:00")),
            pngChunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta/>")),
        )
        assertTrue(uncarriedMetadataIn(ByteArrayInputStream(png(ihdr, bigIdat, *stamps.toTypedArray(), iend))).isEmpty())
    }

    @Test
    fun `a large text chunk is judged by its keyword and its body is skipped`() {
        val longPrompt = pngChunk("iTXt", ascii("Description\u0000\u0000\u0000\u0000\u0000") + ByteArray(500_000))
        val stream = CountingStream(png(ihdr, bigIdat, longPrompt, iend))

        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(stream))
        assertTrue(stream.bytesRead < 1_000)
    }

    @Test
    fun `a text chunk with no data and a text chunk cut off in its keyword both count`() {
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(ByteArrayInputStream(png(ihdr, pngChunk("tEXt", ByteArray(0)), iend))))
        val cut = png(ihdr, pngChunk("tEXt", ascii("parameters\u0000abc"))).let { it.copyOf(it.size - 16) }
        assertEquals(setOf(UncarriedMetadata.PNG_TEXT), uncarriedMetadataIn(ByteArrayInputStream(cut)))
    }

    @Test
    fun `the walk stops at IEND and ignores what follows it`() {
        assertTrue(uncarriedMetadataIn(ByteArrayInputStream(png(ihdr, iend, prompt))).isEmpty())
    }

    @Test
    fun `a chunk length that runs off the end ends the walk without throwing`() {
        val hostile = pngSignature + be32(Int.MAX_VALUE) + ascii("IDAT") + ByteArray(20)
        assertTrue(uncarriedMetadataIn(ByteArrayInputStream(hostile)).isEmpty())
    }

    @Test
    fun `the stream form agrees with the head form on small files`() {
        val files = listOf(
            png(ihdr, pngChunk("tEXt", ascii("Comment\u0000x")), pngChunk("IDAT", ByteArray(40)), iend),
            png(ihdr, pngChunk("IDAT", ByteArray(40)), pngChunk("tEXt", ascii("Software\u0000x")), iend),
            webp(webpChunk("VP8X", ByteArray(10)), webpChunk("VP8 ", ByteArray(31)), webpChunk("ZZZZ", ByteArray(6))),
            webp(webpChunk("VP8 ", ByteArray(31)), webpChunk("EXIF", ByteArray(5)), webpChunk("XMP ", ByteArray(3))),
            ascii("GIF89a...."),
        )
        files.forEach { assertEquals(uncarriedMetadataIn(it), uncarriedMetadataIn(ByteArrayInputStream(it))) }
    }

    @Test
    fun `an extra WebP chunk after a large bitstream is seen by the walk`() {
        val file = webp(webpChunk("VP8X", ByteArray(10)), webpChunk("VP8 ", ByteArray(700_001)), webpChunk("ZZZZ", ByteArray(6)))
        val head = file.copyOf(UNCARRIED_SCAN_BYTES)
        val stream = CountingStream(file)

        assertTrue(uncarriedMetadataIn(head).isEmpty())
        assertTrue(metadataNeedsFullWalk(head))
        assertEquals(setOf(UncarriedMetadata.WEBP_CHUNK), uncarriedMetadataIn(stream))
        assertTrue(stream.bytesRead < 1_000)
    }

    @Test
    fun `a large WebP with only carried chunks stays clean`() {
        val file = webp(webpChunk("VP8X", ByteArray(10)), webpChunk("VP8 ", ByteArray(700_000)), webpChunk("EXIF", ByteArray(12)), webpChunk("XMP ", ascii("<x/>")))
        assertTrue(uncarriedMetadataIn(ByteArrayInputStream(file)).isEmpty())
    }

    @Test
    fun `only a PNG or WebP that fills the window needs the full walk`() {
        val small = png(ihdr, iend)
        val bigJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(UNCARRIED_SCAN_BYTES)

        assertFalse(metadataNeedsFullWalk(small))
        assertFalse(metadataNeedsFullWalk(bigJpeg.copyOf(UNCARRIED_SCAN_BYTES)))
    }

    @Test
    fun `a JPEG is judged from the window as before`() {
        val extended = ascii("http://ns.adobe.com/xmp/extension/\u0000") + ByteArray(8)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(((extended.size + 2) shr 8).toByte(), (extended.size + 2).toByte()) + extended
        assertEquals(setOf(UncarriedMetadata.EXTENDED_XMP), uncarriedMetadataIn(ByteArrayInputStream(jpeg) as InputStream))
    }
}
