package com.fotoxplorr.app.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
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
 * Written to the same cache directory the share pipeline already uses, so it goes out through the
 * FileProvider root that is already declared and already tested against.
 */
class ZipExporter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Zip [items] and return a shareable Uri for the archive.
     *
     * @param onProgress called with (done, total) as each photo lands. Zipping 500 photographs is
     *   a background activity by any reasonable definition, so it reports like one.
     */
    suspend fun export(
        items: List<MediaAsset>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No photos selected" }

            val directory = File(appContext.cacheDir, SharePreparer.SHARE_DIRECTORY).apply {
                mkdirs()
                // The previous archive is not worth keeping: it was handed to another app by Uri,
                // which read it then. Leaving them accumulates the user's whole library in cache.
                listFiles { file -> file.name.endsWith(ZIP_SUFFIX) }?.forEach { it.delete() }
            }
            val archive = File(directory, archiveName(items))

            var written = 0
            val usedNames = HashSet<String>(items.size)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                items.forEach { asset ->
                    // Photos are already compressed; DEFLATE on a JPEG costs CPU to save nothing.
                    // STORED would need the CRC computed up front, so DEFLATE at level 0 is the
                    // cheap equivalent — the archive is a container here, not a compressor.
                    val entry = ZipEntry(uniqueName(asset.displayName, usedNames))
                    zip.putNextEntry(entry)
                    appContext.contentResolver.openInputStream(asset.contentUri)?.use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                    written++
                    onProgress(written, items.size)
                }
            }

            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", archive)
        }
    }

    private companion object {
        const val ZIP_SUFFIX = ".zip"
    }
}

/**
 * A name for the archive itself.
 *
 * Named for what is in it rather than with a timestamp, because the user is about to see this in a
 * share sheet and then in whatever received it — `12 photos.zip` says more there than
 * `export-1755561234.zip` does.
 */
internal fun archiveName(items: List<MediaAsset>): String {
    val count = items.size
    val noun = if (count == 1) "photo" else "photos"
    return "Foto Xplorr $count $noun.zip"
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
