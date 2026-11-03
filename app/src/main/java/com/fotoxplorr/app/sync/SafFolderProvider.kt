package com.fotoxplorr.app.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * A folder, as the provider sees it: paths relative to the root the person picked. The real one is
 * [SafTreeFileSystem] (the system document-tree picker); tests use a plain directory.
 * Blocking; failures are [IOException]s.
 */
interface SafFileSystem {
    val rootLabel: String

    /** False when the permission to the folder was revoked or the folder is gone. */
    fun isRootUsable(): Boolean

    fun stat(path: String): RemoteEntry?
    fun list(folder: String): List<RemoteEntry>

    /** Makes [path] and its parents; existing folders are fine. */
    fun mkdirs(path: String)

    /** A stream writing the file at [path]. A name that exists is an [IOException] unless [truncateExisting]. */
    fun create(path: String, truncateExisting: Boolean): OutputStream
}

/**
 * Sends copies into any folder the person picks with the system folder picker: the phone's storage, a
 * card, a USB disk, or a folder another app serves (a cloud app's Drive or OneDrive folder). Works in
 * BOTH builds because Fotoz itself opens no connection: with a cloud app's folder it is that app that
 * moves the bytes later, outside Fotoz's knowledge (a "done" here means the folder has the file, not
 * that the cloud has it).
 *
 * Writes go straight to the final name. A file cut short is a half-sent file of ours that the next run
 * overwrites ([SyncBegun]); it is never recorded as done.
 */
class SafFolderProvider(private val fs: SafFileSystem) : SyncProvider {
    override val label: String get() = fs.rootLabel
    override val needsNetwork: Boolean = false

    // Written byte counts are checked in put(); what the folder says afterwards is the provider's business.
    override val sizeReportReliable: Boolean = false

    override fun testConnection(cancel: Cancellation): String {
        if (!fs.isRootUsable()) throw SyncException(SyncFailure.DESTINATION_GONE, SyncCopy.FOLDER_GONE)
        return SyncCopy.FOLDER_OK
    }

    override fun stat(path: String): RemoteEntry? = io { fs.stat(requireSafe(path)) }

    override fun list(folder: String): List<RemoteEntry> = io { fs.list(requireSafe(folder)) }

    override fun ensureFolder(path: String) = io { fs.mkdirs(requireSafe(path)) }

    override fun put(
        path: String,
        input: InputStream,
        length: Long,
        overwrite: Boolean,
        cancel: Cancellation,
        onBytes: (Long) -> Unit,
    ): RemoteEntry? {
        requireSafe(path)
        if (!fs.isRootUsable()) throw SyncException(SyncFailure.DESTINATION_GONE, SyncCopy.FOLDER_GONE)
        val existing = io { fs.stat(path) }
        if (existing != null && !overwrite) throw SyncException(SyncFailure.REJECTED, "A file with this name is already in the folder")
        var written = 0L
        io {
            fs.create(path, truncateExisting = existing != null).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    cancel.throwIfCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    onBytes(written)
                }
                out.flush()
            }
        }
        if (written != length) {
            throw SyncLocalChanged("The file changed while it was being copied ($written of $length bytes)")
        }
        return io { fs.stat(path) }
    }

    private fun requireSafe(path: String): String {
        if (path.isNotEmpty() && !RemoteLayout.isSafePath(path)) throw SyncException(SyncFailure.REJECTED, "That path is not allowed")
        return path
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: SyncException) {
        throw e
    } catch (e: IOException) {
        val text = e.message.orEmpty().lowercase(Locale.ROOT)
        val failure = when {
            DestinationFull.matches(e) -> SyncFailure.NO_SPACE
            text.contains("permission") || text.contains("revoked") -> SyncFailure.DESTINATION_GONE
            else -> SyncFailure.TRANSIENT
        }
        throw SyncException(failure, e.message ?: "The folder could not be written", e)
    } catch (e: SecurityException) {
        throw SyncException(SyncFailure.DESTINATION_GONE, SyncCopy.FOLDER_GONE, e)
    }

    private companion object {
        const val BUFFER = 128 * 1024
    }
}

internal object DestinationFull {
    fun matches(error: Throwable): Boolean {
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
