package com.fotoxplorr.app.share

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Packs the selected photos into one zip and hands it over.
 *
 * This exists because the owner's action bar has a zip glyph on it (2026-08-18). The slot was
 * carrying share instead, which made the icon a lie; an icon that does something other than what it
 * draws is worse than no icon. `java.util.zip` is in the JDK, so this costs the offline flavour
 * nothing — no dependency, no network, nothing for the classpath gate to object to.
 *
 * P0-06: entries now come from [SharePreparer]'s own per-item preparation -- the exact pipeline a
 * plain share already runs -- instead of streaming each asset's original bytes straight into the
 * archive. That closes the gap this class used to have with every other share path: an archive
 * built the old way shipped originals (GPS, camera serials, the works) regardless of the user's
 * share preference. A frame or watermark is never applied here (a zip has no share-sheet preview
 * to show one in, and the point of an archive export is the files themselves, not a branded
 * presentation of them) -- see [export]'s own [ShareOptions] construction.
 *
 * Written to the same cache directory the share pipeline already uses, so it goes out through the
 * FileProvider root that is already declared and already tested against.
 */
class ZipExporter(
    context: Context,
    private val sharePreparer: SharePreparer = SharePreparer(context),
) {
    private val appContext = context.applicationContext

    /**
     * Zip [items] and return a shareable Uri for the archive, plus any item [SharePreparer]
     * couldn't prepare (P0-06) -- skipped from the archive rather than written as an empty or
     * unstripped entry, and reported to the caller the same way an ordinary share already reports
     * its own per-item failures ([com.fotoxplorr.app.unpreparedShareItemsMessage]).
     *
     * @param stripMetadata the user's saved share preference -- zip export follows the exact same
     *   default-safe policy as every other share, not a separate always-on/always-off choice.
     * @param onProgress called with (done, total) as each photo lands. Zipping 500 photographs is
     *   a background activity by any reasonable definition, so it reports like one.
     */
    suspend fun export(
        items: List<MediaAsset>,
        stripMetadata: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<ZipExport> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No photos selected" }

            // allowRender = false is what actually guarantees "never a frame or watermark" here --
            // watermark = false alone would not: SharePreparer.prepare re-resolves the watermark
            // against Pro status regardless of what this class asks for (by design, see its own
            // class doc), so a non-Pro sharer's zip would otherwise still get rendered and marked.
            val options = ShareOptions(frame = ShareFrame.NONE, stripMetadata = stripMetadata, watermark = false)
            val prepared = sharePreparer.prepare(items, options, allowRender = false).getOrThrow()
            val failed = prepared.filterIsInstance<PreparedItem.Failed>()
            // Paired by index rather than carried on PreparedItem.Ready itself (which has no asset
            // reference of its own): SharePreparer.prepare returns one PreparedItem per input item,
            // in order, so zipping the two lists back together recovers each entry's real name.
            val ready = items.zip(prepared).mapNotNull { (asset, item) ->
                (item as? PreparedItem.Ready)?.let { asset to it }
            }
            check(ready.isNotEmpty()) { "None of the ${failed.size} items could be prepared for the archive." }

            val directory = File(appContext.cacheDir, SharePreparer.SHARE_DIRECTORY).apply { mkdirs() }
            val archive = File(directory, archiveName(ready.size, items.any { it.isVideo }))

            var written = 0
            val usedNames = HashSet<String>(ready.size)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                // Photos are already compressed; DEFLATE on a JPEG costs CPU to save nothing.
                // STORED would need the CRC computed up front, so DEFLATE at level 0 is the cheap
                // equivalent — the archive is a container here, not a compressor.
                zip.setLevel(Deflater.NO_COMPRESSION)
                ready.forEach { (asset, item) ->
                    val entry = ZipEntry(uniqueName(entryNameFor(asset.displayName, item), usedNames))
                    zip.putNextEntry(entry)
                    appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                        input.copyTo(zip)
                    } ?: error("Could not read the prepared copy of ${asset.displayName}")
                    zip.closeEntry()
                    written++
                    onProgress(written, ready.size)
                }
            }

            ZipExport(FileProvider.getUriForFile(appContext, fileProviderAuthority(appContext), archive), failed)
        }
    }
}

/** [ZipExporter.export]'s result: the archive itself, plus any input item left out of it because
 * [SharePreparer] couldn't prepare it -- never empty entries, per P0-06. */
data class ZipExport(val uri: Uri, val failed: List<PreparedItem.Failed>)

/**
 * A name for the archive itself.
 *
 * Named for what is in it rather than with a timestamp, because the user is about to see this in a
 * share sheet and then in whatever received it — `12 photos.zip` says more there than
 * `export-1755561234.zip` does. [count] is how many entries the archive actually holds (a failed
 * item is skipped, not counted), and [includesVideo] says "items" instead of "photos" when the
 * original selection had any video in it (P0-06) -- "12 photos.zip" would undersell a mixed batch.
 */
internal fun archiveName(count: Int, includesVideo: Boolean): String {
    val singular = if (includesVideo) "item" else "photo"
    val noun = if (count == 1) singular else "${singular}s"
    return "Foto Xplorr $count $noun.zip"
}

/**
 * The zip entry name for one prepared item: [originalDisplayName]'s own stem, but with the
 * extension [item] actually decodes as -- never [originalDisplayName] verbatim. [SharePreparer]
 * can re-encode an item to a different format entirely (an unsupported HEIC re-encoded to PNG to
 * strip its location, say); a zip has no separate MIME channel the way a share [Intent] does, so
 * the file's name is the only thing that tells a receiving app or OS what it actually holds once
 * extracted, and a stale extension left over from the original would be a real, if quiet,
 * correctness bug -- content that lies about what it is.
 */
internal fun entryNameFor(originalDisplayName: String, item: PreparedItem.Ready): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType)
        ?: originalDisplayName.substringAfterLast('.', "")
    if (extension.isEmpty()) return originalDisplayName
    val stem = originalDisplayName.substringBeforeLast('.', originalDisplayName)
    return "$stem.$extension"
}

/**
 * A zip entry name that has not been used yet in this archive.
 *
 * Duplicate entry names are the failure this prevents, and it is not hypothetical: a real library
 * has `IMG_0001.jpg` in several folders, and `ZipOutputStream` throws `ZipException: duplicate
 * entry` partway through — after the user has waited for two hundred photographs to be written.
 * Collisions get ` (2)` before the extension, which is what every file manager does.
 */
internal fun uniqueName(displayName: String, used: MutableSet<String>): String {
    val safe = displayName.ifBlank { "photo" }.replace('/', '_').replace('\\', '_')
    if (used.add(safe)) return safe

    val dot = safe.lastIndexOf('.')
    val stem = if (dot > 0) safe.substring(0, dot) else safe
    val extension = if (dot > 0) safe.substring(dot) else ""
    var index = 2
    while (true) {
        val candidate = "$stem ($index)$extension"
        if (used.add(candidate)) return candidate
        index++
    }
}
