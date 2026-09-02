package com.fotoxplorr.app.media

import com.fotoxplorr.app.formats.MediaFormat
import com.fotoxplorr.app.formats.RawVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [MediaAsset.format] is a thin computed wrapper over [MediaFormat.classify] -- the
 * classification rules themselves are pinned in `MediaFormatTest`. What matters here is only
 * that the property actually feeds it [MediaAsset.mimeType] and [MediaAsset.displayName], not
 * some other pair of fields, since a mix-up there would silently misclassify every asset built
 * from a real MediaStore row.
 */
class MediaAssetFormatTest {

    private fun asset(mimeType: String, displayName: String) = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://media/external/file/1",
        displayName = displayName,
        mimeType = mimeType,
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 0,
        height = 0,
        sizeBytes = 0L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `a RAW asset exposes its RAW format and reports it as not likely decodable`() {
        val nef = asset(mimeType = "", displayName = "IMG_9001.NEF")
        assertEquals(MediaFormat.Raw(RawVariant.NEF), nef.format)
        assertFalse(nef.format.isLikelyDecodable)
    }

    @Test
    fun `an SVG asset classifies as Svg even though MediaStore never marks it as an image`() {
        val svg = asset(mimeType = "image/svg+xml", displayName = "logo.svg")
        assertEquals(MediaFormat.Svg, svg.format)
    }

    @Test
    fun `an ordinary jpeg classifies as Jpeg`() {
        val jpeg = asset(mimeType = "image/jpeg", displayName = "photo.jpg")
        assertEquals(MediaFormat.Jpeg, jpeg.format)
    }
}
