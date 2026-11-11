package com.fotoxplorr.app.media

import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.RawVariant
import com.fotoxplorr.core.formats.raw.RawPreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeRoutesTest {

    @Test
    fun `a raw tries our preview before the platform codec that drops orientation`() {
        val nef = MediaFormat.Raw(RawVariant.NEF)
        assertEquals(listOf(DecodeStep.RawPreview, DecodeStep.Platform), DecodeRoutes.forUpright(nef))
        assertEquals(listOf(DecodeStep.RawPreview), DecodeRoutes.forTile(nef))
        // DNG too: the preview is upright, Skia's full raw decode is the fallback.
        assertEquals(DecodeStep.RawPreview, DecodeRoutes.forUpright(MediaFormat.Raw(RawVariant.DNG)).first())
    }

    @Test
    fun `a raw container the extractor cannot walk goes to the platform only`() {
        assertEquals(listOf(DecodeStep.Platform), DecodeRoutes.forUpright(MediaFormat.Raw(RawVariant.X3F)))
        assertTrue(DecodeRoutes.forTile(MediaFormat.Raw(RawVariant.CRW)).isEmpty())
    }

    @Test
    fun `tiff tries the full decode then the raw preview then the platform`() {
        assertEquals(
            listOf(DecodeStep.SimpleFormat, DecodeStep.RawPreview, DecodeStep.Platform),
            DecodeRoutes.forUpright(MediaFormat.Tiff),
        )
        assertEquals(listOf(DecodeStep.SimpleFormat, DecodeStep.RawPreview), DecodeRoutes.forTile(MediaFormat.Tiff))
    }

    @Test
    fun `the raw hook no longer runs for ordinary formats`() {
        for (f in listOf(MediaFormat.Jpeg, MediaFormat.Png, MediaFormat.Heif, MediaFormat.Other, MediaFormat.Video)) {
            assertEquals(listOf(DecodeStep.Platform), DecodeRoutes.forUpright(f))
            assertTrue(DecodeRoutes.forTile(f).isEmpty())
        }
        assertEquals(listOf(DecodeStep.Platform, DecodeStep.SimpleFormat), DecodeRoutes.forUpright(MediaFormat.Psd))
    }

    @Test
    fun `a tiff never passes a thumbnail or a single strip off as the picture`() {
        assertFalse(DecodeRoutes.acceptsPreview(MediaFormat.Tiff, RawPreviewKind.IFD_THUMBNAIL))
        assertFalse(DecodeRoutes.acceptsPreview(MediaFormat.Tiff, RawPreviewKind.STRIP_JPEG))
        assertTrue(DecodeRoutes.acceptsPreview(MediaFormat.Tiff, RawPreviewKind.SUB_IFD_PREVIEW))
        // A labelled RAW takes whatever is best, a thumbnail included.
        assertTrue(DecodeRoutes.acceptsPreview(MediaFormat.Raw(RawVariant.CR2), RawPreviewKind.STRIP_JPEG))
        assertTrue(DecodeRoutes.acceptsPreview(MediaFormat.Raw(RawVariant.NEF), RawPreviewKind.IFD_THUMBNAIL))
    }

    @Test
    fun `a tiff whose header says raw tries the preview before the whole-file decode`() {
        assertEquals(listOf(DecodeStep.RawPreview, DecodeStep.SimpleFormat), DecodeRoutes.forTile(MediaFormat.Tiff, rawLike = true))
        assertEquals(DecodeRoutes.forTile(MediaFormat.Tiff), DecodeRoutes.forTile(MediaFormat.Tiff, rawLike = false))
        // The hint only reorders a TIFF.
        assertEquals(DecodeRoutes.forTile(MediaFormat.Tga), DecodeRoutes.forTile(MediaFormat.Tga, rawLike = true))
    }
}
