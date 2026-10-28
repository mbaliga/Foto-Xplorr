package com.fotoxplorr.app.animate

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.lift.pruneStaleFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What kind of file an animation is saved as. */
enum class AnimOutput(val extension: String, val mime: String) {
    GIF("gif", "image/gif"),

    /**
     * Saved as `.png` with the PNG type, not as an `.apng` file: every viewer and every media index knows
     * `image/png`, a plain PNG viewer shows the first frame, and an APNG-aware one plays it. The cost is
     * that the name does not say it moves.
     */
    APNG("png", "image/png"),

    /** H.264 in MP4, no sound. */
    VIDEO("mp4", "video/mp4"),
    ;

    val isVideo: Boolean get() = this == VIDEO

    /** The planner's format for this output; a video has no planner format and uses the GIF one only for frame picking, not size. */
    val planFormat: AnimFormat get() = if (this == APNG) AnimFormat.APNG else AnimFormat.GIF
}

/** A photo or video became protected before the animation was saved. */
class AnimateProtectedException : IOException("a photo became protected before the animation was saved")

/** The phone is nearly out of space and the animation could not be written. */
class AnimateNoSpaceException(cause: Throwable? = null) : IOException("the phone has no space left for the animation", cause)

/**
 * Where a finished animation goes: a place to build the file, and the one function that makes a
 * library row. The real one is [AnimateWriter]; tests use a fake that records what it was given.
 */
interface AnimateSink {
    /** A directory the job may write its working files in (frames, the encoded file). Created if missing. */
    fun stagingDirectory(): File

    /**
     * Save the complete file [staged] as a new picture or video dated [dateTakenMillis] and named
     * after [sourceName], and return its content URI. [stillAllowed] is asked once more right before
     * the row is made; when it says no, throw [AnimateProtectedException] and create nothing. On any
     * failure or cancellation no row or file may remain. [onProgress] is 0..1 over the sink's own work.
     */
    suspend fun publish(
        staged: File,
        output: AnimOutput,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String
}

/**
 * Saves a finished animation as a new row in the device's Pictures (or, for a video, its video
 * collection), in this app's own `Fotoz/Animations` album, the way [com.fotoxplorr.app.pano.PanoramaWriter]
 * saves a panorama: the file is complete in this app's cache first, and only then is a MediaStore row made.
 *
 * - **Staged first.** The job builds the file in [stagingDirectory]; when it asks to publish, and
 *   [AnimateSink.publish]'s `stillAllowed` says the sources may still be used (TRAPS #33), a pending row is
 *   created, the bytes are copied in, and the row is made visible. A cancel, an encode failure or a
 *   photo protected mid-job therefore never creates a row; a failure while the bytes are copied deletes
 *   the pending row.
 * - **A new file always.** The name is the first source's stem plus `-animation` ([animationFileName]),
 *   and a number when that name is taken. Nothing already in the library is ever replaced.
 * - **No metadata is copied.** The bytes are made by this app's encoders, which write no EXIF, so no
 *   GPS (or camera, or owner text) from the sources can reach them; the capture date goes into the
 *   row's `DATE_TAKEN` only: the first source's.
 *
 * ## What the guarantees depend on
 * Exactly what [com.fotoxplorr.app.pano.PanoramaWriter] says for its own: on Android 10 and later the row
 * is pending while the bytes are copied and the file lands in `Pictures/Fotoz/Animations/`; on Android 8
 * and 9 there is no pending state and no folder, so the row is visible while copied and the file lands in
 * the default folder, and a process kill during the copy can leave a truncated file nothing removes. A cancel
 * or an exception always deletes the row, also a cancel that arrives after the row was made visible.
 * That the platform accepts `Pictures/` for a video row is taken from [com.fotoxplorr.app.media.mediaStoreRelativePath]'s
 * table; nothing has run on a device.
 */
class AnimateWriter(
    context: Context,
    /** The bytes free on the volume of a directory; the tests say how full the phone is. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : AnimateSink {
    private val appContext = context.applicationContext

    override fun stagingDirectory(): File = File(appContext.cacheDir, STAGING_DIRECTORY).apply {
        if (!mkdirs() && !isDirectory) throw IOException("Could not prepare animation storage")
    }

    override suspend fun publish(
        staged: File,
        output: AnimOutput,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String {
        // The row made visible by the last step of the copy, kept outside the block below: a cancel
        // that lands after it makes withContext throw its result away, and the row must not outlive it.
        var visible: Uri? = null
        try {
            return withContext(Dispatchers.IO) {
                pruneStaging()
                currentCoroutineContext().ensureActive()
                if (!stillAllowed()) throw AnimateProtectedException()
                currentCoroutineContext().ensureActive()
                val uri = try {
                    insertFrom(staged, output, sourceName, dateTakenMillis, onProgress)
                } catch (e: IOException) {
                    throw explain(e)
                }
                visible = uri
                onProgress(1f)
                uri.toString()
            }
        } catch (e: CancellationException) {
            // Cancelled means nothing is left: also when the cancel came after the row was visible.
            visible?.let { uri -> withContext(NonCancellable) { runCatching { appContext.contentResolver.delete(uri, null, null) } } }
            throw e
        }
    }

    /**
     * A failed save as the job should hear it: [AnimateNoSpaceException] when the phone is nearly full,
     * otherwise [failure] itself. A refusal for protection is not a save failure and passes through.
     */
    private fun explain(failure: IOException): IOException {
        if (failure is AnimateProtectedException || failure is AnimateNoSpaceException) return failure
        val low = runCatching { freeSpace(appContext.cacheDir) < LOW_SPACE_BYTES }.getOrDefault(false)
        return if (low) AnimateNoSpaceException(failure) else failure
    }

    /** Delete working files left by a run the system killed. Cheap; called at the start of every save. */
    fun pruneStaging(maxAgeMillis: Long = STAGING_MAX_AGE_MILLIS): Int =
        pruneStaleFiles(File(appContext.cacheDir, STAGING_DIRECTORY), System.currentTimeMillis(), maxAgeMillis)

    /** Create the pending row, copy [staged] into it, make it visible; any failure or cancel deletes the row. */
    private suspend fun insertFrom(staged: File, output: AnimOutput, sourceName: String, dateTakenMillis: Long, onProgress: (Float) -> Unit): Uri {
        val resolver = appContext.contentResolver
        val taken = if (dateTakenMillis > 0) dateTakenMillis else System.currentTimeMillis()
        val collection = if (output.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dateColumn = if (output.isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, animationFileName(sourceName, output) { nameTaken(resolver, collection, it) })
            put(MediaStore.MediaColumns.MIME_TYPE, output.mime)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(dateColumn, taken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                // Keeps the half-written file out of every other gallery until the bytes are there.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Android would not create a new file")
        try {
            val total = staged.length().coerceAtLeast(1L)
            resolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(COPY_CHUNK)
                var copied = 0L
                staged.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, n)
                        copied += n
                        onProgress((copied.toFloat() / total).coerceAtMost(COPY_END))
                    }
                }
            } ?: throw IOException("Could not open the new file for writing")
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val shown = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, shown, null, null) < 1) throw IOException("Android would not finish the new file")
            }
        } catch (t: Throwable) {
            // No pending, empty or half-written row is left behind, whatever stopped the save.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        reassertDateTaken(resolver, uri, dateColumn, taken)
        return uri
    }

    private fun nameTaken(resolver: ContentResolver, collection: Uri, name: String): Boolean = runCatching {
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection = if (onQ) "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?" else
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = if (onQ) arrayOf(name, GALLERY_RELATIVE_PATH) else arrayOf(name)
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    // Making the row visible can rescan the file and replace DATE_TAKEN with the (absent) metadata date.
    private fun reassertDateTaken(resolver: ContentResolver, uri: Uri, column: String, expected: Long) {
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && (cursor.isNull(0) || cursor.getLong(0) != expected)) {
                    resolver.update(uri, ContentValues().apply { put(column, expected) }, null, null)
                }
            }
        }
    }

    companion object {
        /** Where saved animations land: their own album, away from the camera roll. */
        const val GALLERY_RELATIVE_PATH = "Pictures/Fotoz/Animations/"

        /** The cache sub-directory the job builds its files in before any row exists. */
        const val STAGING_DIRECTORY = "animate-staging"

        /** An hour: longer than any job, shorter than anything worth keeping. */
        const val STAGING_MAX_AGE_MILLIS = 60L * 60 * 1000

        /** Under this much free space a failed write is called "no space": the failure cannot say why it happened. */
        const val LOW_SPACE_BYTES = 8L * 1024 * 1024

        private const val COPY_CHUNK = 64 * 1024
        private const val COPY_END = 0.99f
    }
}

/**
 * The file name a saved animation gets: the first source's stem, `-animation`, and the extension of
 * [output]; if [taken] says that name exists, `-animation-2`, `-animation-3` and so on. The stem is
 * cut to a sane length and path separators in it become underscores (a display name with a slash
 * makes MediaStore throw).
 */
internal fun animationFileName(sourceName: String, output: AnimOutput, taken: (String) -> Boolean): String {
    val trimmed = sourceName.trim().ifBlank { "animation" }
    val dot = trimmed.lastIndexOf('.')
    val stem = (if (dot > 0) trimmed.substring(0, dot) else trimmed)
        .replace('/', '_').replace('\\', '_').take(MAX_STEM).removeSuffix("-animation")
    val ext = output.extension
    var candidate = "$stem-animation.$ext"
    var number = 2
    while (taken(candidate)) {
        // A library with a thousand animations of one photo gets a name nobody else can have.
        candidate = if (number <= MAX_NUMBER) "$stem-animation-$number.$ext" else "$stem-animation-${System.nanoTime()}.$ext"
        if (number > MAX_NUMBER) break
        number++
    }
    return candidate
}

private const val MAX_STEM = 100
private const val MAX_NUMBER = 1000
