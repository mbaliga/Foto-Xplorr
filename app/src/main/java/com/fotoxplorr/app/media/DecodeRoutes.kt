package com.fotoxplorr.app.media

import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.raw.RawPreviewKind
import com.fotoxplorr.core.formats.raw.RawPreviewProvider
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders

/** One way of turning a file into pixels. */
internal enum class DecodeStep {
    /** The container's embedded JPEG via [RawPreviewReader], container orientation applied. */
    RawPreview,

    /** The pure-Kotlin decoders of `core/formats/simple` via [SimpleFormatReader]. */
    SimpleFormat,

    /** Android's own codecs (ImageDecoder, then BitmapFactory with EXIF orientation). */
    Platform,
}

/**
 * Which decoders run, in which order, for a format. Pure so the order is a JVM test; [decodeUpright]
 * and [RouteCoilDecoder] (tiles, viewer) both follow it.
 *
 *  - RAW the extractor understands: our preview FIRST. Android's raw codec (piex) would also find
 *    that preview on API 28+ but drops the container's orientation, so a portrait RAW came out
 *    sideways (TRAPS #34); the platform is only the fallback.
 *  - TIFF: the full baseline decode first, then the RAW preview (an extension-less DNG or NEF sniffs
 *    as TIFF), then the platform. On this route a RAW thumbnail or a single JPEG strip is NOT
 *    accepted ([acceptsPreview]): for a plain TIFF that would pass a fragment off as the picture.
 *  - The other simple formats: platform, then ours (tiles go straight to ours: Coil's platform
 *    decoder runs after a null from our decoder anyway).
 *  - Everything else: platform only. The RAW hook no longer runs for arbitrary failed files.
 */
internal object DecodeRoutes {

    fun forUpright(format: MediaFormat): List<DecodeStep> = when {
        RawPreviewProvider.handles(format) -> listOf(DecodeStep.RawPreview, DecodeStep.Platform)
        format == MediaFormat.Tiff -> listOf(DecodeStep.SimpleFormat, DecodeStep.RawPreview, DecodeStep.Platform)
        SimpleFormatDecoders.supports(format) -> listOf(DecodeStep.Platform, DecodeStep.SimpleFormat)
        else -> listOf(DecodeStep.Platform)
    }

    /** The steps our per-request Coil decoder tries; an empty list means "Coil's own decoders only". */
    fun forTile(format: MediaFormat): List<DecodeStep> = forUpright(format).filter { it != DecodeStep.Platform }

    /** Whether a preview of [kind] may stand in for the picture of a file resolved as [format]. */
    fun acceptsPreview(format: MediaFormat, kind: RawPreviewKind): Boolean =
        format is MediaFormat.Raw || (kind != RawPreviewKind.IFD_THUMBNAIL && kind != RawPreviewKind.STRIP_JPEG)
}
