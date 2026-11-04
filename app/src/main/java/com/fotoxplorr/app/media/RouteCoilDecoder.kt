package com.fotoxplorr.app.media

import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.pxOrElse
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.simple.DecodedImage
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders
import com.fotoxplorr.core.formats.simple.SimpleLimits
import com.fotoxplorr.core.formats.simple.SimpleResult
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The Coil side of [DecodeRoutes]: [MediaImage] attaches it per request for a RAW, a TIFF or one of
 * the simple formats, so tiles and the viewer get the RAW embedded preview (container orientation
 * applied) and the pure-Kotlin decoders. It runs before Coil's own decoders; a null result hands
 * the SAME, unconsumed source on to them (Coil 3 keeps searching its decoder list on null, and every
 * read of that source here is a small header `peek`), so the platform still gets its turn before the
 * tile shows a placeholder.
 *
 * Sizes follow the request ([TileSizing]): a Crop tile (Scale.FILL) gets a bitmap whose short edge
 * covers the tile, anything else fits inside the box. The RAW preview is read through the content
 * [uri] with a positional channel (a RAW is never read whole); a simple format is read once through
 * the [uri] into one array ([BoundedBytes]) under the tile caps of [TileSizing.TILE_INPUT], decoded at
 * full size (the decoders have no sampled entry point), averaged down into a tile-sized array and
 * only then made a Bitmap. UNVERIFIED on a device.
 */
internal class RouteCoilDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
    private val format: MediaFormat,
    private val uri: Uri,
) : Decoder {

    private val reqW = options.size.width.pxOrElse { -1 }.takeIf { it > 0 }
    private val reqH = options.size.height.pxOrElse { -1 }.takeIf { it > 0 }
    private val fill = options.scale == Scale.FILL

    override suspend fun decode(): DecodeResult? {
        val rawLike = format == MediaFormat.Tiff && TiffRawHint.looksLikeRaw(peekHeader(TiffRawHint.HEADER_BYTES))
        for (step in DecodeRoutes.forTile(format, rawLike)) {
            val decoded = when (step) {
                DecodeStep.RawPreview -> RawPreviewReader.decode(
                    options.context, uri, { w, h -> TileSizing.limitsFor(w, h, reqW, reqH, fill) },
                ) { DecodeRoutes.acceptsPreview(format, it) }
                DecodeStep.SimpleFormat -> simpleGate.withPermit { decodeSimple() }
                DecodeStep.Platform -> null
            } ?: continue
            return DecodeResult(image = decoded.bitmap.asImage(), isSampled = decoded.downscaled)
        }
        return null
    }

    /** Up to [count] bytes from the start of Coil's source, which stays unread for the decoders after this one. */
    private fun peekHeader(count: Int): ByteArray = runCatching {
        val peek = result.source.source().peek()
        peek.request(count.toLong())
        peek.readByteArray(minOf(peek.buffer.size, count.toLong()))
    }.getOrDefault(ByteArray(0))

    private fun decodeSimple(): DecodedBitmap? {
        val image = decodeFull(TileSizing.simpleLimits(reqW, reqH)) ?: return null
        val (tw, th) = TileSizing.target(image.width, image.height, reqW, reqH, fill)
        val same = tw == image.width && th == image.height
        val pixels = if (same) image.argb else TileSizing.downscale(image.argb, image.width, image.height, tw, th)
        val bitmap = Bitmap.createBitmap(pixels, tw, th, Bitmap.Config.ARGB_8888)
        return DecodedBitmap(bitmap, image.width, image.height, downscaled = !same)
    }

    /** Its own function so the file's bytes are unreachable before the pixels are averaged down. */
    private fun decodeFull(limits: SimpleLimits): DecodedImage? {
        val bytes = BoundedBytes.read(options.context, uri, limits.maxInputBytes) ?: return null
        return (SimpleFormatDecoders.decode(format, bytes, limits) as? SimpleResult.Success)?.image
    }

    class Factory(private val format: MediaFormat, private val uri: Uri) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (DecodeRoutes.forTile(format).isEmpty()) return null
            // A simple-format name over bytes that sniff as something else (a mislabelled JPEG) is
            // left to Coil's own decoders. RAW and TIFF are not checked: their containers overlap.
            if (SimpleFormatDecoders.supports(format) && format != MediaFormat.Tiff) {
                val sniffed = result.source.source().peek().let { p ->
                    p.request(FormatSniffer.REQUIRED_HEADER_BYTES.toLong())
                    FormatSniffer.sniff(p.readByteArray(minOf(p.buffer.size, FormatSniffer.REQUIRED_HEADER_BYTES.toLong())))
                }
                if (sniffed != null && sniffed != format) return null
            }
            return RouteCoilDecoder(result, options, format, uri)
        }
    }

    private companion object {
        /** Bounds how many full-size simple decodes hold memory at once (see [TileSizing.TILE_INPUT]). */
        val simpleGate = Semaphore(TileSizing.SIMPLE_DECODES_AT_ONCE)
    }
}
