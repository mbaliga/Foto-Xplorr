package com.fotoxplorr.app.fileops

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class CleanShareExporter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val authority = "${appContext.packageName}.files"

    suspend fun createCopies(items: List<MediaAsset>): Result<List<Uri>> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No media selected" }
            require(items.all { !it.isVideo && it.mimeType.startsWith("image/") }) {
                "Metadata-clean sharing currently supports images only"
            }

            val directory = File(appContext.cacheDir, CLEAN_SHARE_DIRECTORY).apply {
                deleteRecursively()
                check(mkdirs() || isDirectory) { "Could not prepare clean-share storage" }
            }

            items.map { asset ->
                val extension = asset.displayName.substringAfterLast('.', "jpg")
                    .lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    ?: "jpg"
                val target = File(directory, "${UUID.randomUUID()}.$extension")
                try {
                    resolver.openInputStream(asset.contentUri)?.use { input ->
                        target.outputStream().buffered().use { output ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                        }
                    } ?: throw IOException("Could not read ${asset.displayName}")
                    stripCommonExif(target)
                    FileProvider.getUriForFile(appContext, authority, target)
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
        }
    }

    private fun stripCommonExif(file: File) {
        val exif = ExifInterface(file.absolutePath)
        STRIPPED_EXIF_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
        exif.saveAttributes()
    }

    private companion object {
        const val CLEAN_SHARE_DIRECTORY = "clean-share"
        const val COPY_BUFFER_SIZE = 128 * 1024

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
    }
}
