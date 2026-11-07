package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.simple.SimpleFormatDecoders
import com.fotoxplorr.core.formats.simple.SimpleLimits
import com.fotoxplorr.core.formats.simple.SimpleResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * The Android seam of the pure-Kotlin simple-format decoders (`core/formats/simple`: TGA, PNM, QOI,
 * Radiance HDR, PCX, ICNS, PSD composite, DDS, baseline TIFF, ORA/KRA merged image). Reads the file
 * into memory under [SimpleLimits.maxInputBytes], decodes to ARGB ints, builds a [Bitmap] and scales
 * it to the app's [targetSize]. Null (the caller's chain continues) for any other format, an
 * oversized file or a decode failure.
 *
 * Runs only AFTER the platform codecs and the RAW preview hook have declined the file, so ordinary
 * photos never pay for the sniff. The decoders already bake orientation in (TIFF); nothing is rotated here.
 *
 * UNVERIFIED on a device: the decoders are exercised on the JVM against the synthetic corpus and
 * hand-built bytes, never against a real camera or editor export, and Bitmap creation is not run.
 */
internal object SimpleFormatReader {

    private val limits = SimpleLimits()

    /** Which simple format [header] (the first bytes) plus the mime/name say this is, or null. */
    internal fun resolveFormat(header: ByteArray, mime: String, displayName: String): MediaFormat? {
        val sniffed = FormatSniffer.sniff(header)
        if (sniffed != null && SimpleFormatDecoders.supports(sniffed)) return sniffed
        if (sniffed != null) return null // a known format that is not ours (JPEG, PNG, RAW...)
        return MediaFormat.classify(mime, displayName).takeIf { SimpleFormatDecoders.supports(it) }
    }

    fun decode(context: Context, uri: Uri, decodeLimits: DecodeLimits): DecodedBitmap? =
        runCatching { decodeOrNull(context, uri, decodeLimits) }.getOrNull()

    private fun decodeOrNull(context: Context, uri: Uri, decodeLimits: DecodeLimits): DecodedBitmap? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val header = readUpTo(stream, FormatSniffer.REQUIRED_HEADER_BYTES) ?: return null
            val format = resolveFormat(header, resolver.getType(uri).orEmpty(), name) ?: return null
            val rest = readUpTo(stream, limits.maxInputBytes - header.size + 1, expectedAtLeast = 0) ?: return null
            if (header.size + rest.size > limits.maxInputBytes) return null
            format to (header + rest)
        } ?: return null
        val result = SimpleFormatDecoders.decode(bytes.first, bytes.second, limits)
        val image = (result as? SimpleResult.Success)?.image ?: return null
        val full = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        val (tw, th) = targetSize(image.width, image.height, decodeLimits)
        val scaled = if (tw == image.width && th == image.height) full else {
            Bitmap.createScaledBitmap(full, max(1, tw), max(1, th), true).also { if (it !== full) full.recycle() }
        }
        return DecodedBitmap(
            bitmap = scaled,
            sourceWidth = image.width,
            sourceHeight = image.height,
            downscaled = tw < image.width || th < image.height,
        )
    }

    /** Up to [max] bytes (fewer at end of stream); null when the stream gives nothing at all and [expectedAtLeast] > 0. */
    private fun readUpTo(stream: InputStream, max: Number, expectedAtLeast: Int = 1): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var left = max.toLong()
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray().takeIf { it.size >= expectedAtLeast }
    }
}
