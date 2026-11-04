package com.fotoxplorr.app.media

import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders
import com.fotoxplorr.core.formats.simple.SimpleLimits
import com.fotoxplorr.core.formats.simple.SimpleResult
import kotlin.math.max

/**
 * The Coil side of [DecodeRoutes]: [MediaImage] attaches it per request for a RAW, a TIFF or one of
 * the simple formats, so tiles and the viewer get the RAW embedded preview (container orientation
 * applied) and the pure-Kotlin decoders. It runs before Coil's own decoders; a null result hands
 * the SAME, unconsumed source on to them (Coil 3 keeps searching its decoder list on null, and every
 * read here is a `peek`), so the platform still gets its turn before the tile shows a placeholder.
 *
 * The RAW preview is read through the content [uri] with a positional channel (a RAW is never read
 * whole); limits come from the request size. UNVERIFIED on a device.
 */
internal class RouteCoilDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
    private val format: MediaFormat,
    private val uri: Uri,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val longEdge = max(options.size.width.pxOrElse { DEFAULT_EDGE }, options.size.height.pxOrElse { DEFAULT_EDGE })
            .coerceIn(1, MAX_EDGE)
        val limits = DecodeLimits(longEdge, longEdge.toLong() * longEdge)
        for (step in DecodeRoutes.forTile(format)) {
            val decoded = when (step) {
                DecodeStep.RawPreview -> RawPreviewReader.decode(options.context, uri, limits) { DecodeRoutes.acceptsPreview(format, it) }
                DecodeStep.SimpleFormat -> decodeSimple(limits)
                DecodeStep.Platform -> null
            } ?: continue
            return DecodeResult(image = decoded.bitmap.asImage(), isSampled = decoded.downscaled)
        }
        return null
    }

    private fun decodeSimple(decodeLimits: DecodeLimits): DecodedBitmap? {
        val limits = SimpleLimits()
        val peek = result.source.source().peek()
        // One byte over the cap is enough to know it is too large; peek leaves the source unread.
        peek.request(limits.maxInputBytes + 1)
        if (peek.buffer.size > limits.maxInputBytes) return null
        val bytes = peek.readByteArray()
        val image = (SimpleFormatDecoders.decode(format, bytes, limits) as? SimpleResult.Success)?.image ?: return null
        val full = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        val (tw, th) = targetSize(image.width, image.height, decodeLimits)
        val bitmap = if (tw == image.width && th == image.height) full else {
            Bitmap.createScaledBitmap(full, max(1, tw), max(1, th), true).also { if (it !== full) full.recycle() }
        }
        return DecodedBitmap(bitmap, image.width, image.height, downscaled = bitmap !== full)
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
        const val DEFAULT_EDGE = 2048
        const val MAX_EDGE = 8192
    }
}
