package com.fotoxplorr.app.audiotags

import android.content.ContentResolver
import android.content.Context
import com.fotoxplorr.app.audio.AudioAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The Android side of [AudioTagCodec]: reads [asset]'s file, rebuilds its tags through the pure
 * codec, and writes the result back.
 *
 * ## Write permission
 * Same shape as [com.fotoxplorr.app.metadata.MetadataWriter.write] and for the same reason (see
 * that class's own doc): this attempts the write and lets a `RecoverableSecurityException` (API
 * 29) or plain `SecurityException` (API 30+, consent never requested) surface through the returned
 * [Result] rather than catching it here. It is the CALLER's job — an `Activity`, the only thing
 * that can launch a system consent screen — to catch that and retry with
 * `MediaStore.createWriteRequest`, exactly the way `FotoXplorrActivity.requestMetadataWrite`
 * already does for photo metadata. See `docs/audio-playback.md` for the exact call this app's
 * Activity is expected to make around [write].
 *
 * ## Why a temp file, and why `"rwt"`
 * The new tag block is very often a different SIZE than the old one (a longer title, a cover image
 * added or removed, ID3 padding recalculated) — this app's other in-place writers
 * ([com.fotoxplorr.app.metadata.MetadataWriter]) can get away with `ExifInterface` mutating a file
 * whose overall length barely changes, but rewriting a tag block is closer to a full container
 * rewrite. Building the whole new file in a private temp file first, then copying it over the
 * original through `"rwt"` (truncating write) rather than `"rw"`, is what keeps a shrink from
 * leaving stale trailing bytes of the old, longer file behind — a hazard `"rw"` alone does not
 * guard against at all.
 */
class AudioTagWriter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    suspend fun read(asset: AudioAsset): AudioTags? = withContext(Dispatchers.IO) {
        resolver.openInputStream(asset.contentUri)?.use { AudioTagCodec.read(it) }
    }

    suspend fun write(asset: AudioAsset, tags: AudioTags): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val original = resolver.openInputStream(asset.contentUri)?.use { it.readBytes() }
                ?: throw IOException("Could not open ${asset.displayName} for reading")

            val tempFile = File.createTempFile("audiotag-", ".tmp", appContext.cacheDir)
            try {
                tempFile.outputStream().use { out -> AudioTagCodec.write(original, out, tags) }

                val descriptor = resolver.openFileDescriptor(asset.contentUri, "rwt")
                    ?: throw IOException("Could not open ${asset.displayName} for writing")
                descriptor.use { fd ->
                    FileOutputStream(fd.fileDescriptor).use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
                    }
                }
                Unit
            } finally {
                tempFile.delete()
            }
        }
    }
}
