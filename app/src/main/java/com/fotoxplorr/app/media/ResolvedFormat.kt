package com.fotoxplorr.app.media

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.core.formats.FormatResolution
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The format a tile should treat [asset] as: MediaStore's mime and the filename first (free), and
 * the file's first [FormatSniffer.REQUIRED_HEADER_BYTES] bytes only when that comes back
 * [MediaFormat.Other] ([FormatResolution]). A grid of ordinary photos opens no file for this.
 * Results (including "nothing recognised") are remembered per file version.
 */
@Composable
internal fun rememberResolvedFormat(asset: MediaAsset): State<MediaFormat> {
    val context = LocalContext.current
    val classified = asset.format
    return produceState(
        initialValue = classified,
        asset.contentUriString, asset.dateModifiedSeconds, classified,
    ) {
        if (!FormatResolution.shouldSniff(classified)) {
            value = classified
            return@produceState
        }
        val key = mediaImageCacheKey(asset.contentUriString, asset.dateModifiedSeconds)
        val sniffed = SniffCache.get(key) ?: withContext(Dispatchers.IO) { readHeaderFormat(context, asset) }
            .also { SniffCache.put(key, it) }
        value = FormatResolution.resolve(classified, sniffed.format)
    }
}

/** A sniff result; [format] null means the bytes matched nothing (or could not be read). */
internal class Sniffed(val format: MediaFormat?)

private object SniffCache {
    private val cache = LruCache<String, Sniffed>(512)
    fun get(key: String): Sniffed? = cache.get(key)
    fun put(key: String, value: Sniffed) { cache.put(key, value) }
}

private fun readHeaderFormat(context: Context, asset: MediaAsset): Sniffed {
    val header = runCatching {
        context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
            val buffer = ByteArray(FormatSniffer.REQUIRED_HEADER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }.getOrNull()
    return Sniffed(header?.let { FormatSniffer.sniff(it) })
}
