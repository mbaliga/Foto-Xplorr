package com.fotoxplorr.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns the photos a user chose to share into files that are safe and branded to send.
 *
 * Replaces `CleanShareExporter`, which could only do one thing (strip EXIF) and only when the user
 * remembered to pick "share without metadata" from a menu. Stripping is now the **default** on
 * every share (owner, 2026-08-15), and the frame options ride the same pass, because both need
 * exactly the same thing: a copy in the cache directory handed out as a FileProvider URI.
 *
 * The original is never opened for writing, in any path through this class.
 */
class SharePreparer(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val authority = "${appContext.packageName}.files"

    /**
     * @return content URIs for the prepared copies, ready to hand to a share Intent.
     */
    suspend fun prepare(
        items: List<MediaAsset>,
        options: ShareOptions,
    ): Result<List<Uri>> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No photos selected" }

            val directory = File(appContext.cacheDir, SHARE_DIRECTORY).apply {
                // Cleared each time: these are transient hand-offs, and a share cache that only
                // grows is a privacy problem as much as a disk one -- it would keep unstripped
                // intermediates of everything ever shared.
                deleteRecursively()
                check(mkdirs() || isDirectory) { "Could not prepare share storage" }
            }

            items.map { asset -> prepareOne(asset, options, directory) }
        }
    }

    private fun prepareOne(asset: MediaAsset, options: ShareOptions, directory: File): Uri {
        // Video cannot be framed or stripped by this path, so it is shared as-is rather than
        // failed. Refusing to share a video because a frame was selected for the photos beside it
        // would be the app being clever at the user's expense.
        val renderable = !asset.isVideo && asset.mimeType.startsWith("image/")
        val target = File(directory, "${UUID.randomUUID()}.${outputExtension(asset, options, renderable)}")

        try {
            if (renderable && options.requiresRender) {
                renderFramed(asset, options, target)
            } else {
                copyRaw(asset, target)
                if (renderable && options.stripMetadata) stripCommonExif(target)
            }
            return FileProvider.getUriForFile(appContext, authority, target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /**
     * Decode, frame, re-encode.
     *
     * A framed share is re-encoded, so EXIF does not survive the round trip regardless of the
     * strip setting -- which means a frame is always at least as private as a plain share, never
     * less. When the user asked to KEEP metadata, the handful of tags worth carrying are copied
     * back onto the output explicitly rather than being silently lost.
     */
    private fun renderFramed(asset: MediaAsset, options: ShareOptions, target: File) {
        val source = decodeBounded(asset) ?: throw IOException("Could not read ${asset.displayName}")
        try {
            val framed = FrameRenderer.render(source, options)
            try {
                // PNG, not JPEG: the stamp's perforations are real transparency, and JPEG has no
                // alpha channel -- encoding it as JPEG would fill every notch with black.
                val format = if (options.frame == ShareFrame.STAMP) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                target.outputStream().buffered().use { out ->
                    check(framed.compress(format, JPEG_QUALITY, out)) {
                        "Could not encode the framed copy"
                    }
                }
            } finally {
                if (framed !== source) framed.recycle()
            }
            if (!options.stripMetadata && options.frame != ShareFrame.STAMP) {
                copyBackKeptExif(asset, target)
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * Decode at a bounded size.
     *
     * A framed share of a 48-megapixel original would allocate hundreds of megabytes for a picture
     * that is about to be posted to a chat app. [MAX_SHARE_EDGE] is generous enough that the
     * result is still a good print-ish size and small enough that it cannot OOM the app.
     */
    private fun decodeBounded(asset: MediaAsset): Bitmap? =
        resolver.openInputStream(asset.contentUri)?.use { stream ->
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (longest / sample > MAX_SHARE_EDGE) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }

    private fun copyRaw(asset: MediaAsset, target: File) {
        resolver.openInputStream(asset.contentUri)?.use { input ->
            target.outputStream().buffered().use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        } ?: throw IOException("Could not read ${asset.displayName}")
    }

    private fun outputExtension(asset: MediaAsset, options: ShareOptions, renderable: Boolean): String {
        if (renderable && options.requiresRender) {
            return if (options.frame == ShareFrame.STAMP) "png" else "jpg"
        }
        return asset.displayName.substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "jpg"
    }

    private fun stripCommonExif(file: File) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            STRIPPED_EXIF_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()
        }
    }

    /** Orientation and date only -- enough that a kept-metadata share is not visibly broken. */
    private fun copyBackKeptExif(asset: MediaAsset, target: File) {
        runCatching {
            val outputExif = ExifInterface(target.absolutePath)
            resolver.openInputStream(asset.contentUri)?.use { input ->
                val sourceExif = ExifInterface(input)
                KEPT_EXIF_TAGS.forEach { tag ->
                    sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
                }
            }
            outputExif.saveAttributes()
        }
    }

    private companion object {
        const val SHARE_DIRECTORY = "outgoing-share"
        const val COPY_BUFFER_SIZE = 128 * 1024
        const val JPEG_QUALITY = 92

        /** Longest edge of a re-rendered share, in pixels. */
        const val MAX_SHARE_EDGE = 3200

        val STRIPPED_EXIF_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
        )

        val KEPT_EXIF_TAGS = listOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME_ORIGINAL,
        )
    }
}
