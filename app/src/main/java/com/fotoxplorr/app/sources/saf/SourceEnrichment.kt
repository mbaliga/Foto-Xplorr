package com.fotoxplorr.app.sources.saf

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.media.orientationTransform
import com.fotoxplorr.app.viewer.parseExifDateTimeOriginal
import com.fotoxplorr.core.formats.MediaFormat
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether the read told us the facts, told us only some, or could not be done. "Unreadable" is NOT "absent". */
enum class EnrichmentOutcome { READ, PARTIAL, UNREADABLE, SKIPPED }

/**
 * What a listing cannot give: upright pixel size, EXIF orientation, date taken (and a duration for
 * video). A null field after [EnrichmentOutcome.READ] means the file does not carry it; after
 * UNREADABLE or PARTIAL it means "not known yet", and the caller records a retry, not an absence.
 * [width] and [height] are upright (already swapped for a 90 or 270 degree orientation).
 */
data class SourceFacts(
    val outcome: EnrichmentOutcome,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
    val dateTakenMillis: Long? = null,
    val durationMillis: Long? = null,
    val bytesRead: Long = 0L,
)

/** Pure assembly of the raw numbers into [SourceFacts]; the part JVM tests pin down. */
object SourceFactsAssembly {
    fun upright(rawWidth: Int?, rawHeight: Int?, orientation: Int?): Pair<Int, Int>? {
        if (rawWidth == null || rawHeight == null || rawWidth <= 0 || rawHeight <= 0) return null
        val swap = orientation != null && orientationTransform(orientation).swapsDimensions
        return if (swap) rawHeight to rawWidth else rawWidth to rawHeight
    }

    /** An EXIF `DateTimeOriginal` string to millis, or null for a blank, zero (`0000:00:00 ...`) or malformed one. */
    fun dateTaken(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("0000")) return null
        return parseExifDateTimeOriginal(text)?.takeIf { it > 0L }
    }
}

/** Reads at most [limit] bytes from the wrapped stream and then reports end of file. Counts what was read. */
internal class BoundedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
    var consumed: Long = 0L
        private set

    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = super.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit - consumed).toInt())
        if (n > 0) consumed += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(minOf(n, limit - consumed).coerceAtLeast(0L))
        if (s > 0) consumed += s
        return s
    }

    override fun markSupported(): Boolean = false
}

/**
 * The second pass of a folder source: reads a bounded head of ONE file and returns [SourceFacts].
 * Runs on [Dispatchers.IO]; reads at most [maxHeadBytes] twice (EXIF, then a bounds-only decode) and never the whole file,
 * except for the optional last resort [allowDecodeFallback] (a tiny decode through [decodeUpright],
 * which does read the file once, for a format neither EXIF nor the bounds pass can measure, and only up to
 * [maxFallbackFileBytes]). Remote-backed documents are [EnrichmentOutcome.SKIPPED] unless the caller asks.
 * Not run on a device; JPEG paths run under Robolectric.
 */
class SourceEnricher(
    context: Context,
    private val maxHeadBytes: Long = DEFAULT_HEAD_BYTES,
    private val allowDecodeFallback: Boolean = false,
    private val maxFallbackFileBytes: Long = 64L * 1024 * 1024,
) {
    private val appContext = context.applicationContext

    suspend fun enrich(uri: Uri, format: MediaFormat, sizeBytes: Long, isVirtual: Boolean = false, allowVirtual: Boolean = false): SourceFacts =
        withContext(Dispatchers.IO) {
            if (isVirtual && !allowVirtual) return@withContext SourceFacts(EnrichmentOutcome.SKIPPED)
            if (format == MediaFormat.Video) return@withContext video(uri)
            image(uri, format, sizeBytes)
        }

    private suspend fun image(uri: Uri, format: MediaFormat, sizeBytes: Long): SourceFacts {
        var read = 0L
        var failed = false
        var orientation: Int? = null
        var date: Long? = null
        var exifW: Int? = null
        var exifH: Int? = null
        try {
            appContext.contentResolver.openInputStream(uri)?.use { raw ->
                val bounded = BoundedInputStream(raw, maxHeadBytes)
                val exif = ExifInterface(bounded)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                    .takeIf { it != ExifInterface.ORIENTATION_UNDEFINED }
                date = SourceFactsAssembly.dateTaken(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                exifW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                    ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
                exifH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                    ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
                read += bounded.consumed
            } ?: run { failed = true }
        } catch (e: Exception) {
            failed = true
        }

        var rawW: Int? = null
        var rawH: Int? = null
        try {
            appContext.contentResolver.openInputStream(uri)?.use { raw ->
                val bounded = BoundedInputStream(raw, maxHeadBytes)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(bounded, null, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    rawW = options.outWidth
                    rawH = options.outHeight
                }
                read += bounded.consumed
            }
        } catch (e: Exception) {
            // The bounds pass is best effort; EXIF may still have the size.
        }
        // BitmapFactory's bounds are the stored grid, so the EXIF orientation still has to be applied.
        // EXIF dimensions of a RAW or TIFF are likewise the stored grid.
        var size = SourceFactsAssembly.upright(rawW ?: exifW, rawH ?: exifH, orientation)

        if (size == null && allowDecodeFallback && sizeBytes in 1..maxFallbackFileBytes) {
            val decoded = try {
                decodeUpright(appContext, uri, DecodeLimits(maxLongEdge = 64, maxPixels = 64L * 64L))
            } catch (e: Exception) {
                null
            }
            if (decoded != null) {
                size = decoded.sourceWidth to decoded.sourceHeight // already upright
                decoded.bitmap.recycle()
            }
        }

        val outcome = when {
            size != null && !failed -> EnrichmentOutcome.READ
            size != null || orientation != null || date != null -> EnrichmentOutcome.PARTIAL
            else -> EnrichmentOutcome.UNREADABLE
        }
        return SourceFacts(outcome, size?.first, size?.second, orientation, date, null, read)
    }

    private fun video(uri: Uri): SourceFacts {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val swap = rotation == 90 || rotation == 270
            val ok = w != null && h != null && w > 0 && h > 0
            SourceFacts(
                outcome = if (ok) EnrichmentOutcome.READ else EnrichmentOutcome.UNREADABLE,
                width = if (ok) (if (swap) h else w) else null,
                height = if (ok) (if (swap) w else h) else null,
                durationMillis = duration,
            )
        } catch (e: Exception) {
            SourceFacts(EnrichmentOutcome.UNREADABLE)
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        /** EXIF sits in the first tens of kilobytes of a JPEG; 1 MiB also reaches the Exif block of HEIC and most RAW. */
        const val DEFAULT_HEAD_BYTES: Long = 1L * 1024 * 1024
    }
}
