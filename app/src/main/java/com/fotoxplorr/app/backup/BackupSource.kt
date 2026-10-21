package com.fotoxplorr.app.backup

import android.content.Context
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * A photo or video opened for copying. [length] is what the provider says the file is now (-1
 * when it will not say); [originalLocation] is true when the bytes are the unredacted original,
 * so the photo's own GPS is in them.
 */
class OpenedSource(
    val input: InputStream,
    val length: Long,
    val originalLocation: Boolean,
) : Closeable {
    override fun close() = input.close()
}

/** Where the writer gets a candidate's bytes. Null from [open] means the file is gone or unreadable. */
fun interface BackupSource {
    fun open(candidate: BackupCandidate): OpenedSource?
}

/**
 * [QuickHash] of [candidate] as it is now: the first and last [QuickHash.SAMPLE] bytes, read
 * without copying the file. Used only to recognise a probable move, so a failure is just null.
 */
fun BackupSource.quickHashOf(candidate: BackupCandidate): String? = try {
    open(candidate)?.use { opened ->
        val size = candidate.sizeBytes
        val head = readFully(opened.input, QuickHash.headLength(size)) ?: return@use null
        val skip = QuickHash.tailOffset(size) - head.size
        if (skip > 0 && !skipFully(opened.input, skip)) return@use null
        val tail = readFully(opened.input, QuickHash.tailLength(size)) ?: return@use null
        QuickHash.of(size, head, tail)
    }
} catch (e: IOException) {
    null
}

private fun readFully(input: InputStream, count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val n = input.read(bytes, filled, count - filled)
        if (n < 0) return null
        filled += n
    }
    return bytes
}

private fun skipFully(input: InputStream, count: Long): Boolean {
    var left = count
    while (left > 0) {
        val n = input.skip(left)
        if (n > 0) {
            left -= n
        } else if (input.read() < 0) {
            return false
        } else {
            left--
        }
    }
    return true
}

/**
 * Opens photos from MediaStore for the encrypted backup.
 *
 * This is a sanctioned caller of [uriForLocationRead] (TRAPS #31, which says why the other export
 * paths must not be). The backup is the one place where the unredacted original is read ON
 * PURPOSE, because a backup that quietly lost every photo's GPS would be a worse copy than the
 * photo it replaces. Where the bytes end up: inside an AES-GCM encrypted pack, in the folder the
 * person picked, readable only with their passphrase. Nothing here hands a plain stream, or a uri
 * that carries the original, to any other app, share or export. When `ACCESS_MEDIA_LOCATION` is not
 * granted the read falls back to the plain uri, [OpenedSource.originalLocation] is false, and the
 * entry's record says so (`EntryFlag.ORIGINAL_LOCATION` absent); the preflight card warns first.
 */
class MediaStoreBackupSource(
    context: Context,
    private val assetsById: Map<Long, MediaAsset>,
) : BackupSource {
    private val appContext = context.applicationContext

    override fun open(candidate: BackupCandidate): OpenedSource? = openById(candidate.mediaId)

    /**
     * The same read by media id. A restore uses it to hash the photos already on the phone the way
     * the backup hashed them (the unredacted original when the location is readable), in memory,
     * to compare; the bytes go nowhere.
     */
    fun openById(mediaId: Long): OpenedSource? {
        val plain: Uri = assetsById[mediaId]?.contentUri ?: return null
        val requested = appContext.uriForLocationRead(plain)
        val opened = openForLocationRead(requested, plain) { uri ->
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")
        } ?: return null
        val (descriptor, original) = opened
        val stream = try {
            descriptor.createInputStream()
        } catch (e: IOException) {
            runCatching { descriptor.close() }
            return null
        }
        return OpenedSource(stream, descriptor.length.takeIf { it >= 0 } ?: -1L, original)
    }
}
