package com.fotoxplorr.app.fileops

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class MediaFileOperations(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    suspend fun copyToTree(
        treeUri: Uri,
        items: List<MediaAsset>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<List<Uri>> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No media selected" }
            val destination = DocumentFile.fromTreeUri(appContext, treeUri)
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: error("The selected folder is not writable")

            buildList {
                items.forEachIndexed { index, asset ->
                    val name = destination.availableName(asset.displayName.ifBlank { fallbackName(asset) })
                    val target = destination.createFile(asset.mimeType.ifBlank { "application/octet-stream" }, name)
                        ?: throw IOException("Could not create $name")
                    try {
                        resolver.openInputStream(asset.contentUri)?.use { input ->
                            resolver.openOutputStream(target.uri, "w")?.use { output ->
                                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                                output.flush()
                            } ?: throw IOException("Could not write $name")
                        } ?: throw IOException("Could not read ${asset.displayName}")
                        add(target.uri)
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                    onProgress(index + 1, items.size)
                }
            }
        }
    }

    suspend fun rename(asset: MediaAsset, requestedName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = sanitizeDisplayName(requestedName, asset.displayName)
            val values = ContentValues(1).apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            }
            val rows = resolver.update(asset.contentUri, values, null, null)
            check(rows > 0) { "Android did not rename this media item" }
            cleanName
        }
    }

    fun sanitizeDisplayName(requestedName: String, originalName: String): String {
        val requested = requestedName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim('.', ' ')
        require(requested.isNotEmpty()) { "A file name is required" }

        val originalExtension = originalName.substringAfterLast('.', "")
        val requestedExtension = requested.substringAfterLast('.', "")
        return when {
            originalExtension.isBlank() -> requested
            requestedExtension.equals(originalExtension, ignoreCase = true) -> requested
            requested.contains('.') -> requested
            else -> "$requested.$originalExtension"
        }.take(MAX_DISPLAY_NAME_LENGTH)
    }

    private fun DocumentFile.availableName(requestedName: String): String {
        if (findFile(requestedName) == null) return requestedName
        val extension = requestedName.substringAfterLast('.', "").takeIf { requestedName.contains('.') }
        val stem = if (extension == null) requestedName else requestedName.removeSuffix(".$extension")
        var counter = 1
        while (counter < MAX_DUPLICATE_ATTEMPTS) {
            val candidate = if (extension == null) "$stem ($counter)" else "$stem ($counter).$extension"
            if (findFile(candidate) == null) return candidate
            counter += 1
        }
        error("Too many files named $requestedName in the selected folder")
    }

    private fun fallbackName(asset: MediaAsset): String =
        "media-${asset.id.value}.${asset.mimeType.substringAfter('/', "bin").substringBefore('+')}"

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 128 * 1024
        const val MAX_DISPLAY_NAME_LENGTH = 240
        const val MAX_DUPLICATE_ATTEMPTS = 10_000
    }
}
