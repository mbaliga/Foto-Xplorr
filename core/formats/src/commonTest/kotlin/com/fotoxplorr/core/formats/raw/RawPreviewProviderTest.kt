package com.fotoxplorr.core.formats.raw

import com.fotoxplorr.core.formats.DecoderProvider
import com.fotoxplorr.core.formats.DecoderRegistry
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.RawVariant
import com.fotoxplorr.core.formats.SizeHint
import com.fotoxplorr.core.formats.simple.SimpleDecoderProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawPreviewProviderTest {

    /** Stands in for the platform provider: refuses RAW the way the app's does. */
    private val platform = object : DecoderProvider {
        override val id = "platform"
        override val priority = 10
        override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?): DecoderVerdict =
            if (format is MediaFormat.Raw && !format.variant.isLikelyDecodable) DecoderVerdict.NotDecodable(FormatCopy.RAW_NO_PREVIEW)
            else if (format == MediaFormat.Tiff) DecoderVerdict.NotDecodable(FormatCopy.needsPack("TIFF image"))
            else DecoderVerdict.Decodable
    }

    @Test
    fun `tiff raf and cr3 shaped raws are decodable through the preview`() {
        val registry = DecoderRegistry(listOf(platform, SimpleDecoderProvider(), RawPreviewProvider()))
        for (v in listOf(RawVariant.CR2, RawVariant.CR3, RawVariant.NEF, RawVariant.ARW, RawVariant.ORF, RawVariant.RAF, RawVariant.RW2)) {
            assertEquals(DecoderVerdict.Decodable, registry.probe(MediaFormat.Raw(v)), v.name)
        }
    }

    @Test
    fun `containers the extractor cannot walk keep the raw refusal`() {
        val registry = DecoderRegistry(listOf(platform, RawPreviewProvider()))
        for (v in RawPreviewProvider.UNSUPPORTED) {
            assertEquals(DecoderVerdict.NotDecodable(FormatCopy.RAW_NO_PREVIEW), registry.probe(MediaFormat.Raw(v)), v.name)
        }
        assertTrue(RawPreviewProvider().probe(MediaFormat.Jpeg, "") is DecoderVerdict.NotDecodable)
    }

    @Test
    fun `an unlabelled tiff based raw sniffed as tiff still reaches a loader`() {
        // FormatResolution turns an extension-less DNG or NEF into Tiff; the registry must not stop
        // it at a placeholder, so the tile's decoder chain (TIFF, RAW preview, platform) gets to try.
        val registry = DecoderRegistry(listOf(platform, SimpleDecoderProvider(), RawPreviewProvider()))
        assertEquals(DecoderVerdict.Decodable, registry.probe(MediaFormat.Tiff))
    }
}
