package com.fotoxplorr.app.viewer.deepzoom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.OrientationOps
import com.fotoxplorr.app.media.applyOrientation
import com.fotoxplorr.app.media.orientationTransform
import java.io.Closeable
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * The one Android seam of deep zoom: reads a TILE of the full-resolution file with
 * [BitmapRegionDecoder] and hands it back upright. Everything that decides WHICH tile
 * ([TilePyramid], [RegionRects]) is pure and tested; this class only does the I/O, and is unverified
 * on a device (listed in the batch report's deviceChecks): that a 100 MP JPEG opens without
 * buffering the whole file, that tiles decode inside the time budget, and that orientation
 * is right on a real rotated photo.
 *
 * Opened from a file descriptor where the provider gives a seekable one (no whole-file copy in
 * memory); a stream otherwise. Decoding is serialised (`BitmapRegionDecoder` is not thread-safe).
 * [open] returns null for anything the platform cannot region-decode, and the viewer then simply
 * shows the ordinary picture.
 */
internal class RegionTileDecoder private constructor(
    private val decoder: BitmapRegionDecoder,
    private val pfd: ParcelFileDescriptor?,
    private val ops: OrientationOps,
    private val rawW: Int,
    private val rawH: Int,
) : Closeable {
    /** The upright photo's size in pixels. */
    val width: Int = RegionRects.uprightSize(rawW, rawH, ops).first
    val height: Int = RegionRects.uprightSize(rawW, rawH, ops).second

    /** Decodes [key]'s tile, upright and at most [TilePyramid.TILE_PX] square; null when empty or failed. */
    fun decode(key: TileKey): Bitmap? {
        val rect = TilePyramid.rectOf(key, width, height)
        if (rect.width <= 0 || rect.height <= 0) return null
        val raw = RegionRects.toRaw(rect, rawW, rawH, ops)
        val options = BitmapFactory.Options().apply {
            inSampleSize = key.sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = synchronized(this) {
            runCatching { decoder.decodeRegion(Rect(raw.left, raw.top, raw.right, raw.bottom), options) }.getOrNull()
        } ?: return null
        return applyOrientation(bitmap, ops)
    }

    override fun close() {
        runCatching { decoder.recycle() }
        runCatching { pfd?.close() }
    }

    /**
     * [close] on a background thread. `recycle()` waits on the same native lock as a `decodeRegion`
     * still running on IO (a sample-64 read of a 100 MP JPEG can take 100+ ms), so closing from the
     * main thread -- a page swipe, the viewer closing -- would drop frames for that long.
     */
    fun closeInBackground() = closeOffMain(this)

    companion object {
        /** Opens [uri] for region reads, or null (unreadable, unsupported format, zero size). Blocking: call off the main thread. */
        fun open(context: Context, uri: Uri): RegionTileDecoder? = runCatching {
            val resolver = context.contentResolver
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            // The descriptor is closed on EVERY way out that does not hand it to a decoder, a throw
            // from the stream fallback included (a HEIC on API 26/27, a JPEG the platform rejects).
            closingOnFailure(pfd) {
                val decoder = openDecoder(context, uri, pfd) ?: return@closingOnFailure null
                if (decoder.width <= 0 || decoder.height <= 0) {
                    decoder.recycle(); return@closingOnFailure null
                }
                RegionTileDecoder(decoder, pfd, orientationTransform(orientation), decoder.width, decoder.height)
            }
        }.getOrNull()

        @Suppress("DEPRECATION")
        private fun openDecoder(context: Context, uri: Uri, pfd: ParcelFileDescriptor?): BitmapRegionDecoder? {
            if (pfd != null) {
                val fromFd = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(pfd)
                    } else {
                        BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                    }
                }.getOrNull()
                if (fromFd != null) return fromFd
            }
            return runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(it)
                    } else {
                        BitmapRegionDecoder.newInstance(it, false)
                    }
                }
            }.getOrNull()
        }
    }
}

/**
 * Runs [block], which takes ownership of [resource] by returning non-null. On null or a throw the
 * resource is closed here (and a throw is rethrown), so a half-open never leaks a descriptor.
 */
internal inline fun <T : Any> closingOnFailure(resource: Closeable?, block: () -> T?): T? {
    val result = try {
        block()
    } catch (t: Throwable) {
        runCatching { resource?.close() }
        throw t
    }
    if (result == null) runCatching { resource?.close() }
    return result
}

/** Closes [closeable] on [executor] (IO by default), never on the caller's thread. */
internal fun closeOffMain(closeable: Closeable, executor: Executor = Dispatchers.IO.asExecutor()) {
    executor.execute { runCatching { closeable.close() } }
}
