package com.fotoxplorr.app.backup

import android.content.ContentResolver
import android.net.Uri
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.core.model.MediaId
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Android half of the "Tags and collections" backup: bytes to and from a document, this
 * phone's catalogue, and the three stores. Every decision (what a file means, which photo an id is,
 * how two libraries merge) is in [MetadataBackup]; this only does the I/O around it, in the order
 * that keeps a failure harmless: read, check and match first, write the stores last.
 */
class MetadataBackupIo(
    private val contentResolver: ContentResolver,
    private val libraryStore: LibraryStore,
    private val favoriteStore: FavoriteStore,
    private val sensitiveStore: SensitiveStore,
) {
    /** Thrown with a sentence meant for the person. */
    class Refused(message: String) : Exception(message)

    /** This phone's photos by id, as [MetadataBackup] wants them. A shared-in file is not part of the library. */
    fun catalogueOf(assets: List<MediaAsset>): Map<Long, MediaIdentity> =
        assets.asSequence().filterNot { it.isExternal }.associate {
            it.id.value to MediaIdentity(it.displayName, it.sizeBytes, it.dateTakenMillis, it.relativePath)
        }

    /** Writes the file to [uri]. Returns the message to show. */
    suspend fun save(uri: Uri, assets: List<MediaAsset>, nowMillis: Long): String = withContext(Dispatchers.IO) {
        val library = parseJson(libraryStore.exportJson().toString()) ?: throw Refused("Your library could not be read.")
        val favorites = favoriteStore.observe().first().mapTo(linkedSetOf()) { it.value }
        val sensitive = sensitiveStore.observe().first().mapTo(linkedSetOf()) { it.value }
        val built = MetadataBackup.buildFile(library, favorites, sensitive, catalogueOf(assets), nowMillis)
        if (built.coverage.catalogueMissing) throw Refused(MetadataBackupCopy.NO_CATALOGUE)
        // Built whole before the document is opened, so a problem never leaves a half-written file.
        val bytes = built.json.stringify().toByteArray(Charsets.UTF_8)
        (contentResolver.openOutputStream(uri, "w") ?: throw IOException("Unable to open the destination"))
            .use { it.write(bytes) }
        MetadataBackupCopy.saved(built.coverage)
    }

    /**
     * Reads [uri] and works out what restoring it would do, without changing anything.
     * Refused (with a sentence) when the file is not a backup this build can restore.
     */
    suspend fun inspect(uri: Uri, assets: List<MediaAsset>): MetadataBackup.PreparedRestore = withContext(Dispatchers.IO) {
        val text = readBounded(uri)
        when (val read = MetadataBackup.read(text)) {
            is MetadataBackup.FileRead.Unusable -> throw Refused(read.reason)
            is MetadataBackup.FileRead.Usable -> MetadataBackup.prepare(read.file, catalogueOf(assets))
        }
    }

    /** Applies [prepared] to the stores. The library goes first: it is the part that can refuse. */
    suspend fun apply(prepared: MetadataBackup.PreparedRestore, mode: ImportMode): String = withContext(Dispatchers.IO) {
        libraryStore.importJson(JSONObject(prepared.library.stringify()), mode).getOrThrow()
        val favorites = prepared.favoriteIds.mapTo(linkedSetOf()) { MediaId(it) }
        val sensitive = prepared.sensitiveIds.mapTo(linkedSetOf()) { MediaId(it) }
        when (mode) {
            ImportMode.MERGE -> {
                favoriteStore.setFavorite(favorites, true)
                sensitiveStore.setSensitive(sensitive, true)
            }
            ImportMode.REPLACE -> {
                favoriteStore.replaceAll(favorites)
                sensitiveStore.replaceAll(sensitive)
            }
        }
        MetadataBackupCopy.done(prepared.summary, mode)
    }

    /** Reads the document as text, refusing one larger than [MetadataBackup.MAX_FILE_BYTES]. */
    private fun readBounded(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Unable to open the backup")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MetadataBackup.MAX_FILE_BYTES) throw Refused("This file is too large to be a Fotoz backup.")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}

/**
 * [runCatching] that lets cancellation through. A job that wraps suspend code in plain
 * `runCatching` turns the cancellation into an ordinary failure and keeps running its next
 * statement (TRAPS #49).
 */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
