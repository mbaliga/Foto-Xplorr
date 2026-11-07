package com.fotoxplorr.core.formats.raw

import com.fotoxplorr.core.formats.DecoderProvider
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.RawVariant
import com.fotoxplorr.core.formats.SizeHint

/**
 * The RAW embedded-preview route as a [DecoderProvider]: a RAW whose container [RawPreviewExtractor]
 * understands (TIFF-based, RAF, CR3) is Decodable, so the tile builds its loader and the app's RAW
 * decoder extracts the preview. The registry probe sees no bytes, so this is a statement about the
 * container; a file with no valid preview ends at the loader as a failed load (placeholder), after
 * the platform codec has had its turn too.
 *
 * Containers the extractor does not walk (CRW's CIFF, X3F, MRW, IIQ) keep the RAW refusal.
 */
class RawPreviewProvider : DecoderProvider {
    override val id: String = "raw-preview"
    override val priority: Int = 30

    override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?): DecoderVerdict = when {
        format !is MediaFormat.Raw -> DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER)
        format.variant in UNSUPPORTED -> DecoderVerdict.NotDecodable(FormatCopy.RAW_NO_PREVIEW)
        else -> DecoderVerdict.Decodable
    }

    companion object {
        /** RAW variants whose container is not TIFF, RAF or CR3 shaped. */
        val UNSUPPORTED: Set<RawVariant> = setOf(RawVariant.CRW, RawVariant.X3F, RawVariant.MRW, RawVariant.IIQ)

        fun handles(format: MediaFormat): Boolean = format is MediaFormat.Raw && format.variant !in UNSUPPORTED
    }
}
