package com.fotoxplorr.app.board

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.lift.pruneStaleFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Saves a finished board as a new PNG or JPEG in the device's Pictures, in this app's own
 * `Fotoz/Boards` album, the way [com.fotoxplorr.app.pano.PanoramaWriter] saves a panorama:
 *
 * - **Staged first.** The picture is encoded into a file in this app's cache, and only when that has
 *   worked, and [BoardSink.publish]'s `stillAllowed` says the photos may still be exported (TRAPS
 *   #33), is a MediaStore row created. A cancel, an encode failure or a photo protected mid-job
 *   therefore never creates a row at all; a failure while the bytes are copied deletes the pending row.
 * - **A new file always.** The name is the board's, and a number when that name is taken
 *   ([BoardExportJob.fileName]); nothing already in the library is ever replaced.
 * - **No metadata is written.** `Bitmap.compress` writes no EXIF, so no GPS (or camera, or owner text)
 *   from the photos on the board can reach the file. The row's `DATE_TAKEN` is the time of the save.
 * - **PNG keeps alpha, JPEG does not.** The caller picks PNG for a see-through board
 *   ([BoardFormats.resolve]); this class writes what it is told.
 *
 * ## What the guarantees depend on
 * - **Android 10 and later**: the row is pending (`IS_PENDING`) while the bytes are copied, so a
 *   process kill mid-copy leaves a row Android hides and later discards, and the file lands in
 *   `Pictures/Fotoz/Boards/`.
 * - **Android 8 and 9 (API 26 to 28)**: `IS_PENDING` and `RELATIVE_PATH` are not set. The row is
 *   visible while the bytes are copied, the file lands in the root of `Pictures/`, and a process kill
 *   during the copy leaves a truncated file that nothing here removes (only the cache is swept). A
 *   cancel or an exception still deletes the row.
 * - **A cancel at the very end**: the last stage (clearing the pending flag) cannot be interrupted, but
 *   the caller's cancellation is only noticed when [publish] returns, so [publish] deletes the row it
 *   just made and rethrows; "Cancelled." never has a file behind it.
 * - **A full phone**: when the encode or the copy fails and the cache volume has under
 *   [LOW_SPACE_BYTES] free, the failure is a [BoardNoSpaceException]. The volume checked is the app's
 *   cache; a library on an SD card can be full without it saying so.
 *
 * Encoding makes no second bitmap (the whole picture is compressed from the one given), so the peak is
 * the picture itself. Nothing here has run on a device.
 */
class BoardWriter(
    context: Context,
    /** The bytes free on the volume of a directory; the tests say how full the phone is. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : BoardSink {
    private val appContext = context.applicationContext

    override suspend fun publish(
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int,
        baseName: String,
        dateTakenMillis: Long,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): BoardPublished {
        // The row made visible by the last step of the copy, kept outside the block below: a cancel
        // that lands after it makes withContext throw its result away, and the row must not outlive it.
        var visible: Uri? = null
        try {
            return withContext(Dispatchers.IO) {
                pruneStaging()
                val staged = try {
                    encodeToStaging(bitmap, format, quality)
                } catch (e: IOException) {
                    throw explain(e)
                }
                try {
                    onProgress(ENCODED)
                    currentCoroutineContext().ensureActive()
                    if (!stillAllowed()) throw BoardProtectedException()
                    currentCoroutineContext().ensureActive()
                    val inserted = try {
                        insertFrom(staged, format, baseName, dateTakenMillis)
                    } catch (e: IOException) {
                        throw explain(e)
                    }
                    visible = inserted.first
                    onProgress(1f)
                    BoardPublished(inserted.first.toString(), inserted.second)
                } finally {
                    staged.delete()
                }
            }
        } catch (e: CancellationException) {
            // Cancelled means nothing is left: also when the cancel came after the row was visible.
            visible?.let { uri -> withContext(NonCancellable) { runCatching { appContext.contentResolver.delete(uri, null, null) } } }
            throw e
        }
    }

    /**
     * A failed save as the job should hear it: [BoardNoSpaceException] when the phone is nearly full,
     * otherwise [failure] itself. A refusal for protection passes through.
     */
    private fun explain(failure: IOException): IOException {
        if (failure is BoardProtectedException || failure is BoardNoSpaceException) return failure
        val low = runCatching { freeSpace(appContext.cacheDir) < LOW_SPACE_BYTES }.getOrDefault(false)
        return if (low) BoardNoSpaceException(failure) else failure
    }

    /** Delete staging files left by a run the system killed. Cheap; called at the start of every save. */
    fun pruneStaging(maxAgeMillis: Long = STAGING_MAX_AGE_MILLIS): Int =
        pruneStaleFiles(File(appContext.cacheDir, STAGING_DIRECTORY), System.currentTimeMillis(), maxAgeMillis)

    private fun encodeToStaging(bitmap: Bitmap, format: OutputFormat, quality: Int): File {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "nothing to save" }
        val directory = File(appContext.cacheDir, STAGING_DIRECTORY).apply {
            if (!mkdirs() && !isDirectory) throw IOException("Could not prepare board storage")
        }
        val file = File.createTempFile("board-", ".${format.extension}", directory)
        try {
            val compress = if (format == OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            FileOutputStream(file).buffered().use { out ->
                if (!bitmap.compress(compress, if (format == OutputFormat.PNG) 100 else quality, out)) throw IOException("Could not encode the board")
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return file
    }

    /** Create the pending row, copy [staged] into it, make it visible; any failure or cancel deletes the row. */
    private suspend fun insertFrom(staged: File, format: OutputFormat, baseName: String, dateTakenMillis: Long): Pair<Uri, String> {
        val resolver = appContext.contentResolver
        val taken = if (dateTakenMillis > 0) dateTakenMillis else System.currentTimeMillis()
        val name = BoardExportJob.fileName(baseName, format) { nameTaken(resolver, it) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                // Keeps the half-written file out of every other gallery until the bytes are there.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android would not create a new image file")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(COPY_CHUNK)
                staged.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("Could not open the new file for writing")
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val shown = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (resolver.update(uri, shown, null, null) < 1) throw IOException("Android would not finish the new file")
            }
        } catch (t: Throwable) {
            // No pending, empty or half-written row is left behind, whatever stopped the save.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        reassertDateTaken(resolver, uri, taken)
        return uri to name
    }

    // Making the row visible can rescan the file and replace DATE_TAKEN with the (absent) EXIF date.
    private fun reassertDateTaken(resolver: ContentResolver, uri: Uri, expected: Long) {
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && (cursor.isNull(0) || cursor.getLong(0) != expected)) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.DATE_TAKEN, expected) }, null, null)
                }
            }
        }
    }

    private fun nameTaken(resolver: ContentResolver, name: String): Boolean = runCatching {
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection = if (onQ) "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?" else
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val args = if (onQ) arrayOf(name, GALLERY_RELATIVE_PATH) else arrayOf(name)
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), selection, args, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    companion object {
        /** Where saved boards land: their own album, away from the camera roll. */
        const val GALLERY_RELATIVE_PATH = "Pictures/Fotoz/Boards/"

        /** The cache sub-directory the encoded picture is written to before any row exists. */
        const val STAGING_DIRECTORY = "board-staging"

        /** An hour: longer than any save, shorter than anything worth keeping. */
        const val STAGING_MAX_AGE_MILLIS = 60L * 60 * 1000

        /** Under this much free space a failed write is called "no space": the encode cannot say why it failed. */
        const val LOW_SPACE_BYTES = 8L * 1024 * 1024

        private const val ENCODED = 0.7f
        private const val COPY_CHUNK = 64 * 1024
    }
}
