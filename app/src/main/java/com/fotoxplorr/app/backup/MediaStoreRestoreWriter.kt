package com.fotoxplorr.app.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaKind
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Writes restored photos and videos into MediaStore the way the app's other writers do (the
 * edited-copy and video-export writers): a new row with `IS_PENDING = 1`, so no other gallery
 * sees a half-written file, the bytes, then `IS_PENDING = 0`, then `DATE_TAKEN` read back and put
 * right if clearing the flag made the scanner re-derive it. A file that fails on the way is deleted,
 * never left as a pending row. Nothing here overwrites: `insert` always makes a new row, and the
 * planner has already chosen a name nothing else has.
 *
 * The new row's id comes straight from the insert uri (`ContentUris.parseId`), which is the exact
 * old-id to new-id fact the metadata restore needs; nothing is guessed from a rescan.
 *
 * A row that a killed process left pending is expired by MediaStore itself: each pending row
 * carries a `DATE_EXPIRES` one day ahead (Android 10 and later), so an interrupted restore leaves
 * nothing behind but an entry that disappears on its own. That is by the platform's documentation,
 * not something this was able to watch happen (see the device checklist).
 *
 * Before Android 10 `RELATIVE_PATH` and `IS_PENDING` do not exist: the file goes to the collection's
 * default folder and the row is complete as soon as it is inserted. [MediaStoreRestoreTarget.canChooseFolder]
 * says so, and a protected photo is not restored there at all ([ProtectedPlacement]).
 */
class MediaStoreRestoreWriter(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun begin(item: RestoreItem): PendingRestoreFile {
        val kind = item.kind ?: throw IOException("Not an image or a video")
        val entry = item.entry
        val collection = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val now = clock()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.targetDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
            if (entry.dateTakenMillis > 0) put(MediaStore.Images.ImageColumns.DATE_TAKEN, entry.dateTakenMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, item.targetRelativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_EXPIRES, now / 1000 + PENDING_LIFETIME_SECONDS)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Android would not create the file")
        val output = try {
            resolver.openOutputStream(uri) ?: throw IOException("Could not open the new file for writing")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return Pending(uri, output, entry.dateTakenMillis)
    }

    private inner class Pending(
        private val uri: Uri,
        override val output: OutputStream,
        private val dateTakenMillis: Long,
    ) : PendingRestoreFile {
        private var finished = false

        override fun commit(): CommittedFile {
            output.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            var modified = 0L
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        // Clearing IS_PENDING can make the scanner re-derive DATE_TAKEN from the file's own
                        // EXIF; put the backed-up value back once if it did (same as the edited-copy writer).
                        if (dateTakenMillis > 0 && !cursor.isNull(0) && cursor.getLong(0) != dateTakenMillis) {
                            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.ImageColumns.DATE_TAKEN, dateTakenMillis) }, null, null)
                        }
                        if (!cursor.isNull(1)) modified = cursor.getLong(1)
                    }
                }
            }
            // Only now is there nothing left to throw away: a commit that failed above is aborted by the caller.
            finished = true
            return CommittedFile(ContentUris.parseId(uri), modified)
        }

        override fun abort() {
            if (finished) return
            finished = true
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private companion object {
        const val PENDING_LIFETIME_SECONDS = 24L * 60 * 60
    }
}

/**
 * The phone for a restore: the app's catalogue as the list of what is here, hashing through the
 * same original-location read the backup used, and [MediaStoreRestoreWriter] for new files.
 *
 * [assets] is a snapshot taken when the restore was prepared. Trashed files are left out (a photo
 * in the bin is not "already here") and so are files shared in from another app.
 */
class MediaStoreRestoreTarget(
    context: Context,
    assets: List<MediaAsset>,
    private val lockedFolders: () -> Set<String>,
    private val writer: MediaStoreRestoreWriter = MediaStoreRestoreWriter(context),
) : RestoreTarget {
    private val library = assets.filter { !it.isTrashed && !it.isExternal }
    private val source = MediaStoreBackupSource(context, library.associateBy { it.id.value })

    override val canChooseFolder: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun localFiles(): List<RestoreLocalFile> = library.map {
        RestoreLocalFile(it.id.value, it.displayName, it.relativePath, it.sizeBytes, it.dateTakenMillis, it.dateModifiedSeconds)
    }

    override fun sha256Of(file: RestoreLocalFile): String? = try {
        source.openById(file.mediaId)?.use { opened ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val n = opened.input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    } catch (e: IOException) {
        null
    }

    override fun lockedFolders(): Set<String> = lockedFolders.invoke()

    override fun begin(item: RestoreItem): PendingRestoreFile = writer.begin(item)

    private companion object {
        const val HASH_BUFFER = 256 * 1024
    }
}
