package com.fotoxplorr.app.media

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders
import com.fotoxplorr.core.formats.simple.SimpleLimits
import com.fotoxplorr.core.formats.simple.SimpleResult
import kotlin.math.max

/**
 * Coil decoder for the tile path: [MediaImage] attaches it per request for the formats
 * [SimpleFormatDecoders] handles, so a TGA, QOI, PSD, TIFF... shows in the grid the same way the
 * viewer's [decodeUpright] hook shows it. Tiles are decoded to at most [TILE_LONG_EDGE] on the long side.
 * Declines (null) when the bytes do not decode, which Coil reports as a load failure and the tile
 * turns into the "couldn't be read" placeholder.
 *
 * UNVERIFIED on a device: written against Coil 3's Decoder API, compiled but never run.
 */
internal class SimpleCoilDecoder(
    private val result: SourceFetchResult,
    private val format: MediaFormat,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val limits = SimpleLimits()
        val source = result.source.source()
        val bytes = source.peek().let { peek ->
            // Bounded read: one byte over the cap is enough to know it is too large.
            peek.request(limits.maxInputBytes + 1)
            if (peek.buffer.size > limits.maxInputBytes) return null
            source.readByteArray()
        }
        val image = (SimpleFormatDecoders.decode(format, bytes, limits) as? SimpleResult.Success)?.image ?: return null
        val full = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        val (tw, th) = targetSize(image.width, image.height, DecodeLimits(TILE_LONG_EDGE, TILE_PIXELS))
        val bitmap = if (tw == image.width && th == image.height) full else {
            Bitmap.createScaledBitmap(full, max(1, tw), max(1, th), true).also { if (it !== full) full.recycle() }
        }
        return DecodeResult(image = bitmap.asImage(), isSampled = bitmap !== full)
    }

    class Factory(private val format: MediaFormat) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // The caller's format came from the name or a sniff; a header that sniffs as something
            // else (a mislabelled JPEG) is left to Coil's own decoders.
            val sniffed = result.source.source().peek().let { p ->
                p.request(FormatSniffer.REQUIRED_HEADER_BYTES.toLong())
                FormatSniffer.sniff(p.readByteArray(minOf(p.buffer.size, FormatSniffer.REQUIRED_HEADER_BYTES.toLong())))
            }
            if (sniffed != null && sniffed != format) return null
            return SimpleCoilDecoder(result, format)
        }
    }

    private companion object {
        const val TILE_LONG_EDGE = 2048
        const val TILE_PIXELS = 4_000_000L
    }
}
