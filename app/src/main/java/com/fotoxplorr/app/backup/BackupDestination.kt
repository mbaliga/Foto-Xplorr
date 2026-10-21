package com.fotoxplorr.app.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file directly inside the backup folder. [sizeBytes] is -1 when the provider would not say. */
data class DestinationFile(val name: String, val sizeBytes: Long)

/**
 * The folder a backup set lives in, as the writer, the loader and the verifier see it: names in,
 * streams out, nothing else. The real one is [SafBackupDestination]; tests use a plain directory.
 *
 * Every call blocks and may fail with an [IOException] (a card pulled out, a provider that went
 * away); none of them returns an empty answer for a failure, because "the folder is empty" and
 * "the folder could not be read" lead to opposite decisions. Run them off the main thread.
 *
 * A destination never overwrites: [create] fails if the name exists, and nothing here replaces a
 * file. [delete] exists only so the writer can remove its own half-written file and so the person
 * can remove leftovers they were shown.
 */
interface BackupDestination {
    /** What the person sees as this folder's name. */
    val label: String

    /** Every file directly in the folder (sub-folders are ignored). */
    fun list(): List<DestinationFile>

    fun open(name: String): InputStream

    /** A new file; [IOException] if [name] already exists or the provider renamed it. */
    fun create(name: String): OutputStream

    /** The provider's current length of [name], or -1 if it will not say. */
    fun sizeOf(name: String): Long

    fun delete(name: String): Boolean

    /** Bytes free where the folder lives, or null when that cannot be known (most providers). */
    fun freeBytes(): Long?
}

/** What a failed write means for the person. Pure, so the matching is pinned by a test. */
object DestinationErrors {
    /** True when [error], or anything it was caused by, says the storage is full. */
    fun isNoSpace(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val text = current.message.orEmpty()
            if (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true) ||
                text.contains("not enough space", ignoreCase = true) || text.contains("disk full", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
