package com.fotoxplorr.app.pano

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import java.io.FileOutputStream
import java.io.IOException

/**
 * A [PanoCanvas] over an `ARGB_8888` [Bitmap], so a 24 MP canvas (96 MB) is native memory and not
 * Java heap. Only the canvas is: each photo the compositor draws from is an `IntArray` on the Java
 * heap (4 bytes a pixel, two alive at a time), which is why [PanoramaJob.startRung] looks at
 * `Runtime.maxMemory()` before the first rung. A new bitmap is all zero, i.e. transparent, which is
 * what the compositor needs.
 *
 * Reads and writes take one lock. The compositor only touches disjoint rectangles, but whether
 * `Bitmap.getPixels` and `setPixels` are safe on one bitmap from several threads is not documented,
 * and the copies are a small share of the time next to the warp (unmeasured on a device), so the
 * lock costs little and removes the question.
 */
class BitmapPanoCanvas(val bitmap: Bitmap) : PanoramaCanvas {
    constructor(width: Int, height: Int) : this(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

    private val lock = Any()

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray) {
        synchronized(lock) { bitmap.getPixels(into, 0, w, x, y, w, h) }
    }

    override fun write(x: Int, y: Int, w: Int, h: Int, from: IntArray) {
        synchronized(lock) { bitmap.setPixels(from, 0, w, x, y, w, h) }
    }

    override fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * Saves a finished panorama as a new JPEG in the device's Pictures, in this app's own
 * `Fotoz/Panoramas` album, the way [com.fotoxplorr.app.lift.StickerExporter.saveToGallery] saves a
 * sticker (a pending row, the bytes, then the row made visible), with these differences:
 *
 * - **Staged first.** The cropped canvas is encoded into a file in this app's cache, and only when
 *   that has worked, and [stillAllowed] says the photos may still be exported (TRAPS #33), is a
 *   MediaStore row created. A cancel, an encode failure or a photo protected mid-job therefore
 *   never creates a row at all; a failure while the bytes are copied deletes the pending row.
 * - **A new file always.** The name is the first photo's stem plus `-panorama`, and a number when
 *   that name is taken ([panoramaFileName]); nothing already in the library is ever replaced.
 * - **No metadata is copied.** The JPEG is written by `Bitmap.compress`, which writes no EXIF, so no
 *   GPS (or camera, or owner text) from the source photos can reach it. The capture date goes into
 *   the row's `DATE_TAKEN` only: the first photo's.
 * - **Cropped, no alpha.** Only the rectangle the compositor found to be fully covered is written.
 *
 * Encoding the crop makes a second bitmap of that size (native memory), so the peak at 24 MP is about
 * two canvases; an [OutOfMemoryError] there is the job's cue to take a smaller rung. Quality is
 * [JPEG_QUALITY], the editor's default. Nothing here has run on a device.
 *
 * ## What the guarantees depend on
 * - **Android 10 and later** (the `Q` branch): the row is pending (`IS_PENDING`) while the bytes are
 *   copied, so a process kill mid-copy leaves a row Android hides and later discards, and the file
 *   lands in `Pictures/Fotoz/Panoramas/`.
 * - **Android 8 and 9 (API 26 to 28)**: `IS_PENDING` and `RELATIVE_PATH` are not set. The row is
 *   visible and can be scanned while the bytes are copied, the file lands in the root of `Pictures/`,
 *   and a name already taken is looked up by display name across every folder. A process kill during
 *   the copy leaves a truncated JPEG that nothing here removes (only the cache is swept on the next
 *   run). A cancel or an exception still deletes the row.
 * - **A cancel at the very end**: the last stage (clearing the pending flag and re-asserting the
 *   date) cannot be interrupted, but the caller's cancellation is only noticed when [publish]
 *   returns, so [publish] deletes the row it just made and rethrows; "Cancelled." never has a file behind it.
 * - **A full phone**: when the encode or the copy fails and the cache volume has under
 *   [LOW_SPACE_BYTES] free, the failure is a [PanoramaNoSpaceException]. The volume checked is the
 *   app's cache; on a phone with the library on an SD card that card can be full without it saying so.
 */
class PanoramaWriter(
    context: Context,
    /** The bytes free on the volume of a directory; the tests say how full the phone is. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : PanoramaSink {
    private val appContext = context.applicationContext

    override fun newCanvas(width: Int, height: Int): PanoramaCanvas = BitmapPanoCanvas(width, height)

    override suspend fun publish(
        canvas: PanoramaCanvas,
        crop: AutoCrop.CropRect,
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
                val bitmap = (canvas as? BitmapPanoCanvas)?.bitmap ?: throw IllegalArgumentException("not a bitmap canvas")
                pruneStaging()
                val staged = try {
                    encodeToStaging(bitmap, crop)
                } catch (e: IOException) {
                    throw explain(e)
                }
                try {
                    onProgress(ENCODED)
                    currentCoroutineContext().ensureActive()
                    if (!stillAllowed()) throw PanoramaProtectedException()
                    currentCoroutineContext().ensureActive()
                    val uri = try {
                        insertFrom(staged, sourceName, dateTakenMillis)
                    } catch (e: IOException) {
                        throw explain(e)
                    }
                    visible = uri
                    onProgress(1f)
                    uri.toString()
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
     * A failed save as the job should hear it: [PanoramaNoSpaceException] when the phone is nearly
     * full (the encode and the copy both fail with an error that does not say why, or with ENOSPC),
     * otherwise [failure] itself. A refusal for protection is not a save failure and passes through.
     */
    private fun explain(failure: IOException): IOException {
        if (failure is PanoramaProtectedException || failure is PanoramaNoSpaceException) return failure
        val low = runCatching { freeSpace(appContext.cacheDir) < LOW_SPACE_BYTES }.getOrDefault(false)
        return if (low) PanoramaNoSpaceException(failure) else failure
    }

    /** Delete staging files left by a run the system killed. Cheap; called at the start of every save. */
    fun pruneStaging(maxAgeMillis: Long = STAGING_MAX_AGE_MILLIS): Int =
        pruneStaleFiles(File(appContext.cacheDir, STAGING_DIRECTORY), System.currentTimeMillis(), maxAgeMillis)

    private fun encodeToStaging(bitmap: Bitmap, crop: AutoCrop.CropRect): File {
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= bitmap.width && crop.bottom <= bitmap.height && crop.area > 0) {
            "the crop must lie on the canvas"
        }
        val directory = File(appContext.cacheDir, STAGING_DIRECTORY).apply {
            if (!mkdirs() && !isDirectory) throw IOException("Could not prepare panorama storage")
        }
        val file = File.createTempFile("pano-", ".jpg", directory)
        try {
            val whole = crop.left == 0 && crop.top == 0 && crop.width == bitmap.width && crop.height == bitmap.height
            val piece = if (whole) bitmap else Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            try {
                FileOutputStream(file).buffered().use { out ->
                    if (!piece.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Could not encode the panorama")
                }
            } finally {
                if (piece !== bitmap) piece.recycle()
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return file
    }

    /** Create the pending row, copy [staged] into it, make it visible; any failure or cancel deletes the row. */
    private suspend fun insertFrom(staged: File, sourceName: String, dateTakenMillis: Long): Uri {
        val resolver = appContext.contentResolver
        val taken = if (dateTakenMillis > 0) dateTakenMillis else System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, panoramaFileName(sourceName) { nameTaken(resolver, it) })
            put(MediaStore.Images.Media.MIME_TYPE, OUTPUT_MIME)
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
        return uri
    }

    private fun nameTaken(resolver: ContentResolver, name: String): Boolean = runCatching {
        val onQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection = if (onQ) "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?" else
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val args = if (onQ) arrayOf(name, GALLERY_RELATIVE_PATH) else arrayOf(name)
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), selection, args, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

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

    companion object {
        const val OUTPUT_MIME = "image/jpeg"

        /** The editor's own default JPEG quality for a new file. */
        const val JPEG_QUALITY = 92

        /** Where saved panoramas land: their own album, away from the camera roll. */
        const val GALLERY_RELATIVE_PATH = "Pictures/Fotoz/Panoramas/"

        /** The cache sub-directory the cropped JPEG is written to before any row exists. */
        const val STAGING_DIRECTORY = "panorama-staging"

        /** An hour: longer than any save, shorter than anything worth keeping. */
        const val STAGING_MAX_AGE_MILLIS = 60L * 60 * 1000

        /** Under this much free space a failed write is called "no space": the encode cannot say why it failed. */
        const val LOW_SPACE_BYTES = 8L * 1024 * 1024

        private const val ENCODED = 0.7f
        private const val COPY_CHUNK = 64 * 1024
    }
}

/**
 * The file name a saved panorama gets: the first photo's stem, `-panorama`, `.jpg`; if [taken] says
 * that name exists, `-panorama-2`, `-panorama-3` and so on. The stem is cut to a sane length and
 * path separators in it become underscores (a display name with a slash makes MediaStore throw).
 */
internal fun panoramaFileName(sourceName: String, taken: (String) -> Boolean): String {
    val trimmed = sourceName.trim().ifBlank { "photo" }
    val dot = trimmed.lastIndexOf('.')
    val stem = (if (dot > 0) trimmed.substring(0, dot) else trimmed)
        .replace('/', '_').replace('\\', '_').take(MAX_STEM).removeSuffix("-panorama")
    var candidate = "$stem-panorama.jpg"
    var number = 2
    while (taken(candidate)) {
        // A library with a thousand panoramas of one photo gets a name nobody else can have.
        candidate = if (number <= MAX_NUMBER) "$stem-panorama-$number.jpg" else "$stem-panorama-${System.nanoTime()}.jpg"
        if (number > MAX_NUMBER) break
        number++
    }
    return candidate
}

private const val MAX_STEM = 100
private const val MAX_NUMBER = 1000
