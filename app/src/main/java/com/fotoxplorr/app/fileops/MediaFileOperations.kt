package com.fotoxplorr.app.fileops

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The result of one [MediaFileOperations.renameBatch] call, asset by asset.
 *
 * A `Result<List<String>>` would have to pick one of two wrong answers the moment a single photo
 * in a forty-photo selection fails: fail the whole batch (the other thirty-nine renames that
 * already succeeded get thrown away, or worse, silently rolled back on a store that doesn't
 * support that) or swallow the one failure into a generic "done". Reporting succeeded and failed
 * as two separate, inspectable lists is what lets the caller say "38 renamed, 2 could not be" —
 * which is the honest answer — instead of either extreme.
 */
data class BulkRenameOutcome(
    val succeeded: List<Pair<MediaAsset, String>>,
    val failed: List<Pair<MediaAsset, String>>,
) {
    val attempted: Int get() = succeeded.size + failed.size
    val allSucceeded: Boolean get() = failed.isEmpty() && succeeded.isNotEmpty()
}

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

    /**
     * Rename every asset in [assets] to a name built from [pattern] — see [RenamePattern] for the
     * token language — applied across the whole selection rather than the `.first()` shortcut a
     * single-asset rename used to fall back to for a multi-selection.
     *
     * Does NOT itself ask Android for the write consent a rename may need on API 30+ — that
     * requires launching a system Activity result, which only a composable can do (via
     * `rememberLauncherForActivityResult`), and this class is plain, non-Activity-aware Kotlin the
     * same way it already is for [rename] and [copyToTree]. The caller is expected to have
     * already obtained consent (or to be on an API level that does not need it) before calling
     * this — see the "Rename" row in `GalleryActionsRoom`, which mirrors exactly the
     * `createWriteRequest` → launch → then-call-through pattern this app already uses for trash
     * and delete.
     *
     * @param onProgress called after each asset is attempted, success or failure alike — renaming
     *   forty photographs one MediaStore row at a time is visibly not instant, and a caller with
     *   no progress signal at all is indistinguishable from one that has hung.
     */
    suspend fun renameBatch(
        assets: List<MediaAsset>,
        pattern: String,
        startAt: Int = 1,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkRenameOutcome = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext BulkRenameOutcome(emptyList(), emptyList())

        val subjects = assets.map { RenameSubject(it.displayName, it.dateTakenMillis, it.dateModifiedSeconds) }
        val plan = runCatching {
            BulkRenamePlanner.plan(pattern, subjects, startAt, existingDisplayNames(assets))
        }.getOrElse { error ->
            // The pattern itself is invalid (blank, or a nonsensical startAt) — every asset fails
            // identically and up front, rather than the caller discovering it only when the first
            // of forty `ContentResolver.update` calls throws.
            return@withContext BulkRenameOutcome(
                succeeded = emptyList(),
                failed = assets.map { it to (error.message ?: "Invalid rename pattern") },
            )
        }

        val succeeded = mutableListOf<Pair<MediaAsset, String>>()
        val failed = mutableListOf<Pair<MediaAsset, String>>()
        assets.forEachIndexed { index, asset ->
            val finalName = plan[index].finalName
            runCatching {
                val values = ContentValues(1).apply { put(MediaStore.MediaColumns.DISPLAY_NAME, finalName) }
                val rows = resolver.update(asset.contentUri, values, null, null)
                check(rows > 0) { "Android did not rename this file" }
            }.onSuccess {
                succeeded += asset to finalName
            }.onFailure { error ->
                failed += asset to renameFailureReason(error)
            }
            onProgress(index + 1, assets.size)
        }
        BulkRenameOutcome(succeeded, failed)
    }

    /**
     * Every display name already occupying a folder any of [assets] lives in, queried straight
     * from MediaStore.
     *
     * Scoped to folders rather than the whole device: MediaStore only actually requires a display
     * name to be unique within one `RELATIVE_PATH`, and querying the whole library's names for a
     * forty-photo rename would be needless work. Deliberately NOT excluding the assets' own
     * current names from the result — that would let a pattern degenerate case land a photo back
     * on some OTHER selected photo's about-to-vacate name, which is exactly the kind of order-of-
     * operations bug [BulkRenamePlanner] exists to make impossible; being slightly more
     * conservative than strictly necessary here costs nothing but an occasional needless `(2)`.
     */
    private fun existingDisplayNames(assets: List<MediaAsset>): Set<String> {
        val paths = assets.mapNotNull { it.relativePath }.toSet()
        if (paths.isEmpty()) return emptySet()

        val names = mutableSetOf<String>()
        runCatching {
            val selection = paths.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" }
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                selection,
                paths.toTypedArray(),
                null,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(nameColumn)?.let(names::add)
                }
            }
        }
        // A failed query (permission oddity, a provider quirk on some OEM skin) leaves `names`
        // whatever it collected before failing, which may be empty. That is the safe direction to
        // fail in: BulkRenamePlanner still de-duplicates everything WITHIN this batch on its own,
        // it just cannot also see neighbours outside it — which only risks an occasional
        // unnecessary "(2)" being skipped in favour of the bare name, never two files merging.
        return names
    }

    /**
     * A human-readable reason one rename in a batch failed, distinguishing the one case that
     * actually needs its own sentence: Android 10 (API 29, and *only* that release) requires each
     * app-unowned file's consent individually via `RecoverableSecurityException`, where API 30+
     * has `MediaStore.createWriteRequest` to gather consent for a whole batch up front instead.
     * Chaining thirty individual system consent dialogs for a 30-photo rename on a Q device would
     * be a worse experience than just saying so, so this reports the limitation rather than
     * attempting it.
     */
    private fun renameFailureReason(error: Throwable): String = when {
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
            "Android 10 needs this photo approved individually — rename it on its own, or update to Android 11+."
        else -> error.message ?: "Android did not allow this file to be renamed."
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
