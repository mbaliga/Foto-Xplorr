package com.fotoxplorr.app.composite

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.AppleIdentifierReader
import com.fotoxplorr.core.formats.composite.AppleIdentifiers
import com.fotoxplorr.core.formats.composite.HeifStereo
import com.fotoxplorr.core.formats.composite.LivePhotoPairing
import com.fotoxplorr.core.formats.composite.MotionPhoto
import com.fotoxplorr.core.formats.composite.MotionPhotoLocator
import com.fotoxplorr.core.formats.composite.MpoReader
import com.fotoxplorr.core.formats.composite.StereoPair
import com.fotoxplorr.core.formats.raw.ByteSource
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What a still's bytes say about it, for the viewer. All null for an ordinary photo. */
internal data class CompositeFacts(
    val motion: MotionPhoto? = null,
    val stereo: StereoPair? = null,
    /** A HEIF that declares a stereo group the platform cannot decode the second eye of. */
    val spatialOnly: Boolean = false,
) {
    val isEmpty: Boolean get() = motion == null && stereo == null && !spatialOnly
}

/**
 * The one Android seam of composite media: opens a content [Uri] as a positional channel and hands
 * it to the pure readers in `core/formats/composite`. Nothing here reads a whole file: the readers
 * ask for headers and a bounded head (512 KB at most), and the clip itself is streamed by
 * [rangeStream]. Pipes and unreadable URIs give nothing.
 *
 * Results are cached in memory only, by uri and modification time (a re-saved file is read again),
 * and nothing is written anywhere: composite media is a presentation, never a library change.
 * Every call does blocking IO: call it off the main thread.
 */
internal object CompositeProbe {
    private val facts = LruCache<String, CompositeFacts>(256)
    private val apple = LruCache<String, Optional>(4096)

    /** An identifier read that has been done: [value] is null when the file had none. */
    class Optional(val value: AppleIdentifiers?)

    private fun key(asset: MediaAsset) = "${asset.contentUriString}@${asset.dateModifiedSeconds}"

    /** Motion photo / stereo facts for a still; videos and formats that cannot carry them return empty. */
    fun facts(context: Context, asset: MediaAsset): CompositeFacts {
        if (asset.isVideo || !mayCarry(asset)) return CompositeFacts()
        facts.get(key(asset))?.let { return it }
        val result = withSource(context, asset.contentUri) { source ->
            val motion = MotionPhotoLocator.locate(source)
            val stereo = if (motion == null) MpoReader.stereoPair(source) else null
            val spatial = motion == null && stereo == null && isHeif(asset) && HeifStereo.find(source) != null
            CompositeFacts(motion, stereo, spatial)
        } ?: CompositeFacts()
        facts.put(key(asset), result)
        return result
    }

    /** Apple's content identifier / burst id, for pairing and stacking. Null when the file has none. */
    fun appleIdentifiers(context: Context, asset: MediaAsset): AppleIdentifiers? {
        val k = key(asset)
        apple.get(k)?.let { return it.value }
        val movie = asset.isVideo
        val value = withSource(context, asset.contentUri) { source ->
            if (movie) AppleIdentifierReader.fromMovie(source) else AppleIdentifierReader.fromStill(source)
        }
        apple.put(k, Optional(value))
        return value
    }

    /** What [appleIdentifiers] already read for this version of [asset], without opening it; null when not read yet. */
    fun cachedAppleIdentifiers(asset: MediaAsset): Optional? = apple.get(key(asset))

    /** Only JPEG and HEIF stills carry a motion clip, an MP index or a stereo group. */
    internal fun mayCarry(asset: MediaAsset): Boolean {
        val mime = asset.mimeType.lowercase()
        val name = asset.displayName.lowercase()
        return mime == "image/jpeg" || mime == "image/mpo" || isHeif(asset) ||
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".mpo")
    }

    private fun isHeif(asset: MediaAsset): Boolean {
        val mime = asset.mimeType.lowercase()
        val name = asset.displayName.lowercase()
        return mime == "image/heic" || mime == "image/heif" || name.endsWith(".heic") || name.endsWith(".heif")
    }

    /** True when [asset] is the movie half of a possible Live Photo (a QuickTime file). */
    fun isLiveMovieCandidate(asset: MediaAsset): Boolean =
        asset.isVideo && LivePhotoPairing.isLiveMovie(asset.mimeType, asset.displayName)

    /** Opens [uri] as a [ByteSource] for [block]; null for a pipe, an unreadable URI or a failed read. */
    fun <T> withSource(context: Context, uri: Uri, block: (ByteSource) -> T?): T? {
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return afd.use {
            val channel = FileInputStream(it.fileDescriptor).channel
            runCatching { block(ChannelByteSource(channel, it.startOffset)) }.getOrNull()
        }
    }

    /**
     * Streams [length] bytes of [uri] from [offset] into [sink]; false when the range could not be
     * read in full (the caller then deletes what it wrote). [sink] receives at most 64 KB at a time.
     */
    fun copyRange(context: Context, uri: Uri, offset: Long, length: Long, sink: (ByteArray, Int) -> Unit): Boolean =
        withRange(context, uri, offset, length) { input ->
            val buffer = ByteArray(COPY_CHUNK)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink(buffer, n)
                total += n
            }
            total == length
        } ?: false

    /** [block] reads [length] bytes of [uri] from [offset] as a stream; null when the file cannot be opened or [block] throws. */
    fun <T> withRange(context: Context, uri: Uri, offset: Long, length: Long, block: (InputStream) -> T?): T? {
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return afd.use {
            val channel = FileInputStream(it.fileDescriptor).channel
            runCatching { rangeStream(channel, it.startOffset + offset, length).use(block) }.getOrNull()
        }
    }

    private fun rangeStream(channel: FileChannel, start: Long, length: Long): InputStream = object : InputStream() {
        private var position = start
        private var remaining = length

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val n = channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), remaining).toInt()), position)
            if (n <= 0) return -1
            position += n
            remaining -= n
            return n
        }
    }

    /** Positional reads on a [FileChannel]; [start] handles an asset that begins mid-file. */
    private class ChannelByteSource(private val channel: FileChannel, private val start: Long) : ByteSource {
        override val length: Long = runCatching { channel.size() - start }.getOrDefault(0L)

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
            runCatching { channel.read(ByteBuffer.wrap(buffer, offset, count), start + position) }.getOrDefault(-1)
    }

    private const val COPY_CHUNK = 64 * 1024
}
