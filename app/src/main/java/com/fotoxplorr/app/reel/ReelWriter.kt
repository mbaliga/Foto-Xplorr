package com.fotoxplorr.app.reel

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Where a finished reel goes: a place to build files, and the one function that makes a library row. The
 * real one is [ReelWriter]; the job's tests use a fake that records what it was given.
 */
interface ReelSink {
    /** A directory the job may write its working files in (pictures, the encoded file). Created if missing. */
    fun stagingDirectory(): File

    /**
     * Save the complete file [staged] as a new video named after [stem] and dated [dateTakenMillis], and return
     * its content URI. [stillAllowed] is asked once more right before the row is made; when it says no, throw
     * [ReelProtectedException] and create nothing. On any failure or cancellation no row or file may remain.
     * [onProgress] is 0..1 over the sink's own work.
     */
    suspend fun publish(staged: File, stem: String, dateTakenMillis: Long, stillAllowed: () -> Boolean, onProgress: (Float) -> Unit): String

    /**
     * Delete working files an export the system killed left behind and nothing else will remove. Cheap, blocking:
     * call it off the main thread, and never while a video is being made (the real one spares anything written lately).
     */
    fun sweepStaging() {}
}

/**
 * Saves a finished reel as a new video in the device's Movies, in this app's own `Fotoz/Reels` album, the
 * way [com.fotoxplorr.app.pano.PanoramaWriter] saves a panorama: the file is complete in this app's cache
 * first, and only then is a MediaStore row made (pending until every byte is across).
 *
 * - **Staged first.** A cancel, an encode failure or a photo protected mid-job therefore never creates a
 *   row; a failure while the bytes are copied deletes the pending row.
 * - **A new file always.** The name is the project's name or the date ([reelStem]) and a number when that
 *   name is taken ([reelFileName]); nothing already in the library is ever replaced.
 * - **No place is copied.** The file is written by [PlaceFreeMuxerFactory], which drops every metadata
 *   entry, and the job looks at the finished file ([ReelMp4Check]) before it gets here. The capture date goes
 *   into the row's `DATE_TAKEN` only: the first picture's.
 *
 * ## What the guarantees depend on
 * Exactly what the panorama writer says for its own: on Android 10 and later the row is pending while the
 * bytes are copied and the file lands in `Movies/Fotoz/Reels/`; on Android 8 and 9 there is no pending
 * state and no folder, so the row is visible while copied and the file lands in the default folder
 * (unverified), and a process kill during the copy can leave a truncated file nothing removes. A cancel or
 * an exception always deletes the row, also a cancel that arrives after the row was made visible. That the
 * platform accepts `Movies/Fotoz/Reels/` is taken from its documented video folders; nothing has run on a device.
 */
class ReelWriter(
    context: Context,
    /** The bytes free on the volume of a directory; the tests say how full the phone is. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : ReelSink {
    private val appContext = context.applicationContext

    override fun stagingDirectory(): File = File(appContext.cacheDir, STAGING_DIRECTORY).apply {
        if (!mkdirs() && !isDirectory) throw IOException("Could not prepare reel storage")
    }

    override suspend fun publish(staged: File, stem: String, dateTakenMillis: Long, stillAllowed: () -> Boolean, onProgress: (Float) -> Unit): String {
        // The row made visible by the last step of the copy, kept outside the block below: a cancel
        // that lands after it makes withContext throw its result away, and the row must not outlive it.
        var visible: Uri? = null
        try {
            return withContext(Dispatchers.IO) {
                pruneStaging()
                currentCoroutineContext().ensureActive()
                if (!stillAllowed()) throw ReelProtectedException()
                currentCoroutineContext().ensureActive()
                val uri = try {
                    insertFrom(staged, stem, dateTakenMillis, onProgress)
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

    /** A failed save as the job should hear it: [ReelNoSpaceException] when the phone is nearly full, otherwise [failure] itself. */
    private fun explain(failure: IOException): IOException {
        if (failure is ReelProtectedException || failure is ReelNoSpaceException) return failure
        val low = runCatching { freeSpace(appContext.cacheDir) < LOW_SPACE_BYTES }.getOrDefault(false)
        return if (low) ReelNoSpaceException(failure) else failure
    }

    override fun sweepStaging() {
        pruneStaging()
    }

    /**
     * Delete working files left by a run the system killed: loose files, and the `job-*` folders the job builds in
     * (a killed export leaves its pictures and half a video there). Cheap; called at the start of every save and
     * when the Make panel opens.
     */
    fun pruneStaging(maxAgeMillis: Long = STAGING_MAX_AGE_MILLIS): Int =
        pruneStaleStaging(File(appContext.cacheDir, STAGING_DIRECTORY), System.currentTimeMillis(), maxAgeMillis)

    /** Create the pending row, copy [staged] into it, make it visible; any failure or cancel deletes the row. */
    private suspend fun insertFrom(staged: File, stem: String, dateTakenMillis: Long, onProgress: (Float) -> Unit): Uri {
        val resolver = appContext.contentResolver
        val taken = if (dateTakenMillis > 0) dateTakenMillis else System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, reelFileName(stem) { nameTaken(resolver, it) })
            put(MediaStore.Video.Media.MIME_TYPE, OUTPUT_MIME)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, taken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                // Keeps the half-written file out of every other gallery until the bytes are there.
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Android would not create a new video file")
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
                val shown = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                if (resolver.update(uri, shown, null, null) < 1) throw IOException("Android would not finish the new file")
            }
        } catch (t: Throwable) {
            // No pending, empty or half-written row is left behind, whatever stopped the save.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        reassertDateTaken(resolver, uri, taken)
        return uri
    }

    private fun nameTaken(resolver: ContentResolver, name: String): Boolean = runCatching {
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection = if (onQ) "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?" else
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
        val args = if (onQ) arrayOf(name, GALLERY_RELATIVE_PATH) else arrayOf(name)
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), selection, args, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    // Making the row visible can rescan the file and replace DATE_TAKEN with the (absent) metadata date.
    private fun reassertDateTaken(resolver: ContentResolver, uri: Uri, expected: Long) {
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Video.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && (cursor.isNull(0) || cursor.getLong(0) != expected)) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.DATE_TAKEN, expected) }, null, null)
                }
            }
        }
    }

    companion object {
        const val OUTPUT_MIME = "video/mp4"

        /** Where saved reels land: their own album in Movies, away from the camera roll. */
        const val GALLERY_RELATIVE_PATH = "Movies/Fotoz/Reels/"

        /** The cache sub-directory the job builds its files in before any row exists. */
        const val STAGING_DIRECTORY = "reel-staging"

        /** An hour: longer than a short reel's export, shorter than anything worth keeping. A very long reel on a slow phone could outlast it. */
        const val STAGING_MAX_AGE_MILLIS = 60L * 60 * 1000

        /** Under this much free space a failed write is called "no space": the failure cannot say why it happened. */
        const val LOW_SPACE_BYTES = 8L * 1024 * 1024

        private const val COPY_CHUNK = 64 * 1024
        private const val COPY_END = 0.99f
    }
}

/**
 * Delete what in [directory] is older than [maxAgeMillis] before [nowMillis]: a file by its own date, a folder (a
 * job's working folder) with all it holds by the newest date of the folder or anything inside it, so an export
 * still writing is spared. Returns how many entries went; a missing directory or an entry that refuses to go is
 * skipped. Not [com.fotoxplorr.app.lift.pruneStaleFiles], which leaves folders alone.
 */
internal fun pruneStaleStaging(directory: File, nowMillis: Long, maxAgeMillis: Long): Int {
    val entries = directory.listFiles() ?: return 0
    var removed = 0
    for (entry in entries) {
        if (nowMillis - newestModified(entry, 0) <= maxAgeMillis) continue
        if ((if (entry.isDirectory) entry.deleteRecursively() else entry.delete())) removed++
    }
    return removed
}

private fun newestModified(entry: File, depth: Int): Long {
    var newest = entry.lastModified()
    if (entry.isDirectory && depth < MAX_STAGING_DEPTH) {
        entry.listFiles()?.forEach { newest = maxOf(newest, newestModified(it, depth + 1)) }
    }
    return newest
}

private const val MAX_STAGING_DEPTH = 4
private const val MAX_STEM = 100
private const val MAX_NUMBER = 1000

/**
 * The name a reel is saved under before its extension: the project's [name] when it has one (path
 * separators and control characters turned into underscores or dropped, a typed `.mp4` removed, cut to a
 * sane length), else `Reel-` and the date of [dateMillis] in [zone].
 */
fun reelStem(name: String, dateMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val cleaned = name.trim()
        .removeSuffix(".mp4").removeSuffix(".MP4")
        .map { if (it == '/' || it == '\\' || it == ':') '_' else it }
        .filter { !it.isISOControl() }
        .joinToString("")
        .trim()
        .trimEnd('.')
        .take(MAX_STEM)
        .trim()
    if (cleaned.isNotEmpty()) return cleaned
    return "Reel-" + Instant.ofEpochMilli(if (dateMillis > 0L) dateMillis else System.currentTimeMillis()).atZone(zone).toLocalDate()
}

/**
 * The file name a saved reel gets: [stem] and `.mp4`; if [taken] says that name exists, `-2`, `-3` and so on.
 * Nothing already in the library is ever replaced.
 */
internal fun reelFileName(stem: String, taken: (String) -> Boolean): String {
    val base = stem.trim().ifBlank { "Reel" }
    var candidate = "$base.mp4"
    var number = 2
    while (taken(candidate)) {
        // A library with a thousand reels of one name gets a name nobody else can have.
        candidate = if (number <= MAX_NUMBER) "$base-$number.mp4" else "$base-${System.nanoTime()}.mp4"
        if (number > MAX_NUMBER) break
        number++
    }
    return candidate
}
