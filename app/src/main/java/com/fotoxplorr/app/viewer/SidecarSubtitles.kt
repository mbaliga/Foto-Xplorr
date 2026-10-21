package com.fotoxplorr.app.viewer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaAsset

/** One file MediaStore's `Files` collection can return alongside a video — just enough to
 *  name-match a subtitle sidecar against, not a real asset model. */
data class SidecarCandidate(
    val displayName: String,
    val relativePath: String?,
    val contentUriString: String,
) {
    val contentUri: Uri get() = Uri.parse(contentUriString)
}

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

/**
 * Pure name-matching, no MediaStore involved: a sidecar subtitle shares the video's own file stem
 * and sits in the same `RELATIVE_PATH`/bucket, differing only in extension. Separated from
 * [querySidecarSubtitlesFor] so the actual matching RULE is unit-testable on the JVM without a
 * `ContentResolver`.
 */
fun findSidecarSubtitles(
    candidates: List<SidecarCandidate>,
    videoStem: String,
    videoRelativePath: String?,
): List<SidecarCandidate> = candidates.filter { candidate ->
    val dot = candidate.displayName.lastIndexOf('.')
    if (dot <= 0) return@filter false
    val stem = candidate.displayName.substring(0, dot)
    val extension = candidate.displayName.substring(dot + 1).lowercase()
    extension in SUBTITLE_EXTENSIONS &&
        stem.equals(videoStem, ignoreCase = true) &&
        candidate.relativePath == videoRelativePath
}

/** The file-name stem (no extension) [findSidecarSubtitles] matches sidecars against. */
fun fileStem(displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    return if (dot > 0) displayName.substring(0, dot) else displayName
}

/**
 * Thin MediaStore glue: everything in the same bucket as [asset], narrowed to real subtitle
 * sidecars by [findSidecarSubtitles]. Android's `Files` collection is the only one that can see a
 * `.srt`/`.vtt`/`.ass`/`.ssa` file at all (the video and audio collections only enumerate their
 * own media types), which is why this queries it rather than `MediaStore.Video`.
 */
fun querySidecarSubtitlesFor(context: Context, asset: MediaAsset): List<SidecarCandidate> {
    val relativePath = asset.relativePath ?: return emptyList()
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
    )
    val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
    val candidates = mutableListOf<SidecarCandidate>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val path = cursor.getString(pathIndex)
                val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
                candidates += SidecarCandidate(name, path, uri.toString())
            }
        }
    }
    return findSidecarSubtitles(candidates, fileStem(asset.displayName), relativePath)
}

/** The sidecar's Media3 subtitle MIME type, or null for an extension [MediaItem.SubtitleConfiguration]
 *  cannot describe -- callers filter these out rather than attaching a guessed MIME type. */
fun subtitleMimeTypeFor(displayName: String): String? = when (displayName.substringAfterLast('.', "").lowercase()) {
    "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
    "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
    else -> null
}
