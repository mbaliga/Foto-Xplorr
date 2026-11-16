package com.fotoxplorr.app.receive

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.fotoxplorr.app.media.MediaKind
import com.fotoxplorr.app.media.mediaStoreRelativePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Copies one received file into the library as a NEW MediaStore row, the way
 * [com.fotoxplorr.app.board.BoardWriter] saves a board:
 *
 * - **Only new files.** The name is the sender's (made safe), and `-2`, `-3` and so on when that name
 *   is taken in the target folder or was already used earlier in this run; nothing already in the
 *   library is ever replaced, and the received file itself is only read.
 * - **Folder.** Photos go to `Pictures/Fotoz/`, videos to `Movies/Fotoz/` ([mediaStoreRelativePath]
 *   is the one place that knows which top folders each collection accepts).
 * - **Off the main thread.** All reading and writing runs on [Dispatchers.IO], in 64 KiB chunks that
 *   each check for a cancel.
 * - **No half files.** Android 10 and later: the row is `IS_PENDING` while the bytes are copied and
 *   a failure, a cancel or a refused finish deletes it, so a cancelled run leaves no pending row.
 *   Android 8 and 9 (API 26 to 28): there is no pending flag and no `RELATIVE_PATH`, so the row is
 *   visible while it is copied, the file lands in the root of `Pictures/` or `Movies/`, and a process
 *   kill mid-copy leaves a truncated file this class cannot remove; a failure or cancel still
 *   deletes the row.
 * - **Metadata is the sender's.** The bytes are copied as they are, so EXIF and GPS inside the file
 *   stay (this is a copy of what was given, not a share-out); the row's `DATE_TAKEN` is the time of
 *   the save and the scanner may replace it from the file.
 *
 * What nothing here shows: the real provider's own refusals and renaming, and the scanner reading
 * the finished file; those are on the device checklist.
 */
class ReceiveWriter(context: Context) {
    private val appContext = context.applicationContext

    /** Saves [item]; returns the name the new file got. [usedNames] is the run's own set of taken names. */
    suspend fun save(item: ReceiveItem, usedNames: MutableSet<String>): String = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val video = item.kind == ReceiveKind.VIDEO
        val kind = if (video) MediaKind.VIDEO else MediaKind.IMAGE
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val mime = item.mimeType ?: fallbackMime(video)
        val folder = mediaStoreRelativePath(null, kind)
        val source = Uri.parse(item.uri)
        val input = resolver.openInputStream(source) ?: throw IOException("Could not read the shared file")
        input.use { stream ->
            val name = uniqueName(resolver, collection, folder, safeName(item.displayName, source, mime), usedNames)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    // Keeps the half-written file out of every other gallery until the bytes are there.
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val row = resolver.insert(collection, values) ?: throw IOException("Android would not create a new file")
            try {
                resolver.openOutputStream(row)?.use { out ->
                    val buffer = ByteArray(COPY_CHUNK)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, n)
                    }
                } ?: throw IOException("Could not open the new file for writing")
                currentCoroutineContext().ensureActive()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val shown = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (resolver.update(row, shown, null, null) < 1) throw IOException("Android would not finish the new file")
                }
            } catch (t: Throwable) {
                // No pending, empty or half-written row is left behind, whatever stopped the save.
                runCatching { resolver.delete(row, null, null) }
                throw t
            }
            usedNames += name
            name
        }
    }

    private fun uniqueName(
        resolver: ContentResolver,
        collection: Uri,
        folder: String,
        wanted: String,
        usedNames: Set<String>,
    ): String {
        val stem = wanted.substringBeforeLast('.', wanted)
        val ext = wanted.substringAfterLast('.', "")
        fun candidate(n: Int) = (if (n == 1) stem else "$stem-$n") + if (ext.isEmpty()) "" else ".$ext"
        for (n in 1..MAX_NUMBER) {
            val name = candidate(n)
            if (name !in usedNames && !nameTaken(resolver, collection, folder, name)) return name
        }
        return "$stem-${System.nanoTime()}" + if (ext.isEmpty()) "" else ".$ext"
    }

    private fun nameTaken(resolver: ContentResolver, collection: Uri, folder: String, name: String): Boolean = runCatching {
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection = if (onQ) "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        else "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = if (onQ) arrayOf(name, folder) else arrayOf(name)
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun fallbackMime(video: Boolean) = if (video) "video/mp4" else "image/jpeg"

    private companion object {
        const val COPY_CHUNK = 64 * 1024
        const val MAX_NUMBER = 1000
    }
}

/**
 * A file name the platform accepts, from what the sender called the file: control characters and
 * the characters Android refuses become underscores, the stem is cut to 80 characters, and a name
 * with no extension gets the one [mime] stands for. Kept apart from the writer so it is pure to test
 * apart from [MimeTypeMap]'s answer, which [extensionFor] supplies.
 */
internal fun safeName(
    displayName: String?,
    source: Uri,
    mime: String,
    extensionFor: (String) -> String? = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) },
): String {
    val raw = displayName?.takeIf { it.isNotBlank() } ?: source.lastPathSegment.orEmpty()
    val cleaned = raw.substringAfterLast('/').filter { !it.isISOControl() }
        .map { if (it in FORBIDDEN) '_' else it }.joinToString("").trim().trim('.')
    val stem = cleaned.substringBeforeLast('.', cleaned).take(MAX_STEM).trim().ifBlank { "Shared" }
    val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        ?: extensionFor(mime).orEmpty()
    return if (ext.isEmpty()) stem else "$stem.$ext"
}

private const val MAX_STEM = 80
private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
