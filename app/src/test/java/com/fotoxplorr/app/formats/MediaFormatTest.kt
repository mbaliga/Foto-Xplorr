package com.fotoxplorr.app.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier the UI leans on to decide "try to decode this" vs. "show an honest
 * placeholder". A wrong answer here is not cosmetic: a RAW variant marked decodable when it
 * isn't shows a broken-image icon in place of a placeholder that would have said why; one
 * marked undecodable when it isn't hides a preview that would have worked.
 */
class MediaFormatTest {

    @Test
    fun `every RAW extension classifies as Raw with its named vendor`() {
        val expected = mapOf(
            "dng" to RawVariant.DNG,
            "cr2" to RawVariant.CR2,
            "cr3" to RawVariant.CR3,
            "nef" to RawVariant.NEF,
            "arw" to RawVariant.ARW,
            "orf" to RawVariant.ORF,
            "rw2" to RawVariant.RW2,
            "raf" to RawVariant.RAF,
            "srw" to RawVariant.SRW,
            "pef" to RawVariant.PEF,
        )
        expected.forEach { (extension, variant) ->
            val format = MediaFormat.classify(mimeType = "", fileName = "IMG_0001.$extension")
            assertEquals("$extension should classify as $variant", MediaFormat.Raw(variant), format)
        }
    }

    @Test
    fun `RAW classification is case-insensitive and does not depend on mime type`() {
        // Real devices report a scattering of vendor mime strings (and sometimes none at all)
        // for RAW files -- the file extension has to carry this on its own. See MediaFormat's
        // KDoc on RawVariant for the full reasoning.
        val format = MediaFormat.classify(mimeType = "application/octet-stream", fileName = "Vacation.ARW")
        assertEquals(MediaFormat.Raw(RawVariant.ARW), format)
    }

    @Test
    fun `only DNG among RAW variants is likely decodable`() {
        assertTrue(RawVariant.DNG.isLikelyDecodable)
        val nonDng = RawVariant.entries - RawVariant.DNG
        nonDng.forEach { variant ->
            assertFalse("$variant should not be marked decodable", variant.isLikelyDecodable)
        }
    }

    @Test
    fun `SVG classifies by mime type even with a mismatched extension`() {
        assertEquals(MediaFormat.Svg, MediaFormat.classify(mimeType = SVG_MIME_TYPE, fileName = "icon"))
    }

    @Test
    fun `SVG classifies by extension when mime type is blank`() {
        assertEquals(MediaFormat.Svg, MediaFormat.classify(mimeType = "", fileName = "logo.svg"))
    }

    @Test
    fun `SVG is likely decodable because coil-svg rasterises it for viewing`() {
        assertTrue(MediaFormat.Svg.isLikelyDecodable)
    }

    @Test
    fun `common raster formats classify correctly`() {
        assertEquals(MediaFormat.Jpeg, MediaFormat.classify("image/jpeg", "photo.jpg"))
        assertEquals(MediaFormat.Png, MediaFormat.classify("image/png", "shot.png"))
        assertEquals(MediaFormat.WebP, MediaFormat.classify("image/webp", "sticker.webp"))
        assertEquals(MediaFormat.Gif, MediaFormat.classify("image/gif", "meme.gif"))
        assertEquals(MediaFormat.Heif, MediaFormat.classify("image/heic", "IMG_1234.heic"))
        assertEquals(MediaFormat.Bmp, MediaFormat.classify("image/bmp", "scan.bmp"))
    }

    @Test
    fun `video mime types classify as Video regardless of extension quirks`() {
        assertEquals(MediaFormat.Video, MediaFormat.classify("video/mp4", "clip.mp4"))
    }

    @Test
    fun `an unrecognised format falls back to Other and stays likely decodable`() {
        val format = MediaFormat.classify(mimeType = "application/pdf", fileName = "receipt.pdf")
        assertEquals(MediaFormat.Other, format)
        // Not a regression: everything unclassified already goes through Coil today with no
        // classifier in the loop at all, so "unknown" must not start producing placeholders.
        assertTrue(format.isLikelyDecodable)
    }

    @Test
    fun `RAW extension wins even when a raster mime type is present`() {
        // A provider that mislabels a RAW file's mime (or defaults it from a stale extension
        // map) must not cause a RAW photo to be treated as an ordinary, always-decodable image.
        val format = MediaFormat.classify(mimeType = "image/jpeg", fileName = "IMG_0007.cr2")
        assertEquals(MediaFormat.Raw(RawVariant.CR2), format)
        assertFalse(format.isLikelyDecodable)
    }
}
