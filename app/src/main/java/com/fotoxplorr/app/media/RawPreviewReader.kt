package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.fotoxplorr.core.formats.raw.ByteSource
import com.fotoxplorr.core.formats.raw.RawPreview
import com.fotoxplorr.core.formats.raw.RawPreviewExtractor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * The Android seam of the pure-Kotlin RAW preview extractor (`core/formats/raw`): opens the content
 * [Uri] as a seekable channel, asks [RawPreviewExtractor] for the largest validated embedded JPEG,
 * then decodes exactly that byte range with [BitmapFactory] (bounds pass, power-of-two sample, exact
 * scale, orientation baked in through the same [targetSize]/[orientationTransform] the rest of the
 * app uses). Nothing here reads a whole RAW: the channel is positional and the preview is streamed
 * through a range-limited [InputStream].
 *
 * Null (the caller keeps its existing path) when the URI is not a seekable file, is not a supported
 * RAW container, no valid preview is found, or the range does not decode.
 *
 * UNVERIFIED on a device: no RAW file has ever been opened through this seam; the vendor layouts
 * behind it are from public documentation, not real camera files.
 */
internal object RawPreviewReader {

    /** Find the preview range without decoding it (null when [uri] has none). */
    fun find(context: Context, uri: Uri): RawPreview? = withChannel(context, uri) { channel, start ->
        RawPreviewExtractor.extract(ChannelByteSource(channel, start))
    }

    fun decode(context: Context, uri: Uri, limits: DecodeLimits): DecodedBitmap? =
        runCatching { decodeOrNull(context, uri, limits) }.getOrNull()

    private fun decodeOrNull(context: Context, uri: Uri, limits: DecodeLimits): DecodedBitmap? =
        withChannel(context, uri) { channel, start ->
            val preview = RawPreviewExtractor.extract(ChannelByteSource(channel, start)) ?: return@withChannel null
            if (preview.length > MAX_PREVIEW_BYTES) return@withChannel null
            val ops = orientationTransform(preview.orientation)
            val uprightW = if (ops.swapsDimensions) preview.height else preview.width
            val uprightH = if (ops.swapsDimensions) preview.width else preview.height
            val (tw, th) = targetSize(uprightW, uprightH, limits)
            val rawTw = if (ops.swapsDimensions) th else tw
            val rawTh = if (ops.swapsDimensions) tw else th
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(preview.width, preview.height, rawTw, rawTh)
            }
            val sampled = BitmapFactory.decodeStream(
                RangeInputStream(channel, start + preview.offset, preview.length),
                null,
                options,
            ) ?: return@withChannel null
            val scaled = if (sampled.width == rawTw && sampled.height == rawTh) {
                sampled
            } else {
                Bitmap.createScaledBitmap(sampled, max(1, rawTw), max(1, rawTh), true).also {
                    if (it !== sampled) sampled.recycle()
                }
            }
            DecodedBitmap(
                bitmap = applyOrientation(scaled, ops),
                sourceWidth = uprightW,
                sourceHeight = uprightH,
                downscaled = tw < uprightW || th < uprightH,
            )
        }

    /** Opens a seekable channel on [uri] (null for pipes and unreadable URIs); [block] gets the
     * channel and the start offset of the data inside it. */
    private fun <T> withChannel(context: Context, uri: Uri, block: (FileChannel, Long) -> T?): T? {
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return afd.use {
            val channel = FileInputStream(it.fileDescriptor).channel
            runCatching { block(channel, it.startOffset) }.getOrNull()
        }
    }

    private const val MAX_PREVIEW_BYTES = 32L shl 20

    /** Positional reads on a [FileChannel]; the shift handles an asset that starts mid-file. */
    private class ChannelByteSource(private val channel: FileChannel, private val start: Long = 0L) : ByteSource {
        override val length: Long = runCatching { channel.size() - start }.getOrDefault(0L)

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
            runCatching { channel.read(ByteBuffer.wrap(buffer, offset, count), start + position) }.getOrDefault(-1)
    }

    /** [length] bytes of [channel] from [offset], as a stream (BitmapFactory wants a fresh one per pass). */
    private class RangeInputStream(
        private val channel: FileChannel,
        private var position: Long,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0 || len == 0) return if (len == 0) 0 else -1
            val n = channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), remaining).toInt()), position)
            if (n <= 0) return -1
            position += n
            remaining -= n
            return n
        }
    }
}
