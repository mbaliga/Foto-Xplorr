package com.fotoxplorr.app.lift

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.fotoxplorr.app.share.fileProviderAuthority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Gets a lifted sticker out of this app: onto the device as a PNG, or into another app's hands.
 *
 * ## Why the Clackpad hand-off is a plain [Intent], not a library dependency
 * The owner wants a hand-off to a sibling app, Clackpad. `shared-libraries/settings.gradle.kts`
 * records the constraint that rules out the obvious way to build that: Clackpad is one of the
 * apps forbidden from depending on Hyle (decision D-L, alongside Animalcules), so integrating
 * through Hyle's design system -- a shared UI/data contract the two apps would both compile
 * against -- is not available here even if it existed for this purpose. Reaching for some OTHER
 * shared library would not dodge that constraint either: any dependency this app takes on
 * anything Clackpad-specific requires Clackpad's own build to expose it, which is exactly the
 * "Clackpad takes a dependency it is forbidden to take" problem stated the other way round, and
 * it would need this app to be built against Clackpad's release cycle, which two independent
 * apps in this constellation are not.
 *
 * A standard Android send [Intent] sidesteps the whole question: it needs neither app to declare
 * anything about the other at compile time. This app puts a `image/png` [Intent.ACTION_SEND] on
 * the system, and the system decides who can take it -- Clackpad if it registers an intent filter
 * for that MIME type and the user picks it from the chooser (or Clackpad is the only such filter
 * on the device, or is preferred, per whatever chooser behaviour the OS provides), or any other
 * app that can accept a PNG, exactly like every other hand-off already in this codebase
 * ([com.fotoxplorr.app.FotoXplorrActivity.shareUris] uses the identical pattern). This file does
 * not hardcode Clackpad's application ID or attempt `setPackage` to target it directly: this repo
 * does not contain Clackpad's manifest, so any package name here would be an unverified guess,
 * and a guess that is wrong fails a hand-off silently -- an implicit intent with no target package
 * degrades to "show the chooser", which always works.
 *
 * The Android side of a Bitmap-out-to-disk operation, same division of labour as
 * [com.fotoxplorr.app.editor.EditedCopyWriter]: no decisions live here, only I/O.
 */
class StickerExporter(context: Context) {
    private val appContext = context.applicationContext
    private val authority = fileProviderAuthority(appContext)

    /**
     * Save [bitmap] as a new PNG in the device's Pictures, in this app's own `Fotoz/Stickers`
     * album ([GALLERY_RELATIVE_PATH]) -- see [com.fotoxplorr.app.editor.EditedCopyWriter], which
     * this mirrors. Runs only from the explicit Save sticker button. A sticker is a new artefact derived from a photo, not an edit of one, so unlike the
     * editor there is no "original" this could ever be mistaken for overwriting.
     */
    suspend fun saveToGallery(bitmap: Bitmap, baseName: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, stickerFileName(baseName))
                put(MediaStore.Images.Media.MIME_TYPE, OUTPUT_MIME)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                    // Keeps the half-written file out of every other gallery on the device until
                    // the bytes are actually there -- see EditedCopyWriter for the failure this
                    // prevents (a scanner indexing a truncated file and showing a corrupt thumbnail).
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android would not create a new image file")

            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    // PNG unconditionally: it is the sticker's whole reason for existing (real
                    // transparency), and JPEG has no alpha channel to encode one into.
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Could not encode the sticker"
                    }
                } ?: error("Could not open the new file for writing")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }
    }

    /**
     * Write [bitmap] to this app's OWN cache sub-directory and hand back a `content://` URI a
     * share [Intent] can grant read access to.
     *
     * Deliberately not [com.fotoxplorr.app.share.SharePreparer]'s `outgoing-share` directory,
     * even though it is already wired to the same [FileProvider] authority and would save one XML
     * entry: that directory is `deleteRecursively()`d at the START of every single call to
     * `SharePreparer.prepare` (see its KDoc), on the theory that its contents are always a
     * disposable, immediately-consumed hand-off. A sticker share can still have its chooser Intent
     * on screen, unresolved, when the user goes and shares a batch of ordinary photos from the
     * gallery in another tab of their attention -- and that share would delete this file out from
     * under the still-open chooser. Two independent callers racing to clear the same "scratch"
     * directory is the bug; a directory each feature owns outright is what removes it.
     */
    suspend fun prepareForShare(bitmap: Bitmap): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching { writeToShareCache(bitmap) }
    }

    /**
     * Put [bitmap] on the system clipboard as an `image/png` content URI, so it can be pasted
     * into a chat or a note with its transparency intact. A clipboard cannot carry pixels, only a
     * URI, so the PNG goes to the same private cache directory a share uses and the clip points
     * at it through the [FileProvider]; the system grants the pasting app read access to exactly
     * that one URI when the clip is read.
     *
     * Always a user action (the Copy button): nothing here runs on its own.
     */
    suspend fun copyToClipboard(bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = writeToShareCache(bitmap)
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newUri(appContext.contentResolver, CLIP_LABEL, uri))
        }
    }

    /**
     * Delete sticker files in this feature's cache directory older than [maxAgeMillis], so copying
     * and sharing -- each of which writes a fresh file -- cannot grow the cache without bound.
     * A day by default: long enough that a chooser the person left open, or a clip they have not
     * pasted yet, still resolves; short enough that nothing lingers. Returns how many were removed.
     */
    suspend fun pruneCache(maxAgeMillis: Long = CACHE_MAX_AGE_MILLIS): Int = withContext(Dispatchers.IO) {
        pruneStaleFiles(File(appContext.cacheDir, SHARE_DIRECTORY), System.currentTimeMillis(), maxAgeMillis)
    }

    private fun writeToShareCache(bitmap: Bitmap): Uri {
        val directory = File(appContext.cacheDir, SHARE_DIRECTORY).apply {
            check(mkdirs() || isDirectory) { "Could not prepare sticker share storage" }
        }
        val target = File(directory, "${UUID.randomUUID()}.png")
        target.outputStream().buffered().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Could not encode the sticker"
            }
        }
        return FileProvider.getUriForFile(appContext, authority, target)
    }

    /**
     * A standard send [Intent] carrying [uri] as an `image/png`. The caller wraps this in
     * [Intent.createChooser] and starts it -- see this file's KDoc for why this is exactly as far
     * as the Clackpad hand-off goes: no target package, no library, just the platform's own
     * mechanism for "here is a file, something on this device can probably use it".
     */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = OUTPUT_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    internal companion object {
        const val OUTPUT_MIME = "image/png"

        /**
         * Must match a `<cache-path>` in `res/xml/file_paths.xml`, or `getUriForFile` throws
         * "Failed to find configured root that contains ..." at the moment of sharing -- the exact
         * failure `com.fotoxplorr.app.share.ShareDirectoryTest` exists to catch for the OTHER
         * share directory. There is no equivalent test on this module boundary yet; the two are
         * kept in sync by inspection, same as they were before that test existed for the other one.
         */
        const val SHARE_DIRECTORY = "lift-stickers"

        /**
         * Where saved stickers land in the device's own gallery: their own album, so a pile of
         * transparent cut-outs does not interleave with the camera roll. Only ever written to by
         * the Save sticker button.
         */
        const val GALLERY_RELATIVE_PATH = "Pictures/Fotoz/Stickers/"

        /** The longest edge of an exported sticker, in pixels; the source is never upscaled to reach it. */
        const val EXPORT_LONG_EDGE_PX = 2048

        const val CLIP_LABEL = "Sticker"

        /** See [StickerExporter.pruneCache]. */
        const val CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * The file name a saved sticker gets: the source photo's stem, a marker, `.png`.
 *
 * Pure and separate from the exporter, mirroring
 * [com.fotoxplorr.app.editor.editedName][com.fotoxplorr.app.editor.editedName] exactly, including
 * its handling of the awkward real-world names (no extension, several dots, a leading dot) --
 * there was no reason to solve that problem differently the second time it came up.
 */
internal fun stickerFileName(displayName: String, marker: String = "sticker"): String {
    val trimmed = displayName.trim().ifBlank { "photo" }
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.png"
}

/**
 * Delete the regular files directly inside [directory] whose last-modified time is more than
 * [maxAgeMillis] before [nowMillis]; returns how many went. A missing directory is not an error
 * (nothing was ever shared), and a file that refuses to delete is skipped rather than aborting the
 * sweep. Pure `java.io`, separate from the exporter so it can be tested without a [Context].
 */
internal fun pruneStaleFiles(directory: File, nowMillis: Long, maxAgeMillis: Long): Int {
    val files = directory.listFiles() ?: return 0
    var removed = 0
    for (file in files) {
        if (file.isFile && nowMillis - file.lastModified() > maxAgeMillis && file.delete()) removed++
    }
    return removed
}
