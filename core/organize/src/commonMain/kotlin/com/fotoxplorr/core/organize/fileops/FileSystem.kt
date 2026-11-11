package com.fotoxplorr.core.organize.fileops

/**
 * The only door the file-ops engine uses to touch storage (ADR-022 point 1). The Android seam
 * implements it for `File` and for SAF documents; tests use an in-memory fake with fault injection.
 * Paths are plain strings with `/` separators; what they mean (a file path, a tree uri plus a
 * relative path) is the implementation's business.
 *
 * Contract: every failure is a [FileOpsException]. A vanished volume is [FileOpsError.DEVICE_GONE],
 * a missing path [FileOpsError.NOT_FOUND]. The engine never calls anything except the members below,
 * and it calls [delete] only on its own temp files and on files already inside a `.fotoz-trash`
 * folder (an explicit Empty trash); it never deletes an original.
 */
interface FileSystem {
    /** Null when the path does not exist. Throws only for a device or permission failure. */
    fun stat(path: String): FileStat?

    /** Child names (not paths) of a directory. */
    fun list(dir: String): List<String>

    fun openRead(path: String): ByteSource

    /** Create a NEW file; throws [FileOpsError.EXISTS] when the path is taken. */
    fun openCreate(path: String): ByteSink

    /** Append, creating the file when absent. Used only for the journal. */
    fun openAppend(path: String): ByteSink

    /** Rename within one source; throws [FileOpsError.EXISTS] rather than replacing [to]. */
    fun rename(from: String, to: String)

    fun delete(path: String)

    fun mkdirs(path: String)

    /** Stable id of the storage volume the path lives on. Equal ids mean a native rename works. */
    fun sourceId(path: String): String

    /** The root folder of that volume; `.fotoz-trash` lives directly under it. */
    fun sourceRoot(path: String): String

    /** Free bytes on the volume, or null when unknown. */
    fun freeBytes(path: String): Long?
}

data class FileStat(val size: Long, val isDirectory: Boolean = false, val modifiedMs: Long = 0L)

interface ByteSource {
    /** Bytes read into [buffer] (at least 1), or -1 at the end. */
    fun read(buffer: ByteArray): Int
    fun close()
}

interface ByteSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

/** The error taxonomy. SOURCE_GONE .. VERIFY_MISMATCH are the ones a person sees; the rest are plumbing. */
enum class FileOpsError {
    /** The original is no longer there (or its drive was pulled while reading it). */
    SOURCE_GONE,
    /** The destination drive vanished while writing. The run stops and can be resumed. */
    DESTINATION_GONE,
    NO_SPACE,
    PERMISSION,
    /** The copy read back differently from the source. Only the temp copy is removed. */
    VERIFY_MISMATCH,
    /** Replace was asked for without the explicit consent flag. Nothing was touched. */
    CONSENT_REQUIRED,
    CANCELLED,
    UNSUPPORTED,
    IO,

    // File-system level kinds, thrown by a FileSystem and mapped by the engine.
    NOT_FOUND,
    EXISTS,
    DEVICE_GONE,

    /** The resume journal could not be written; the run stops before the next action. */
    JOURNAL_UNWRITABLE,
}

class FileOpsException(val error: FileOpsError, message: String = error.name) : Exception(message)

/** Path helpers. Only `/` is a separator. */
object FilePaths {
    fun name(path: String): String = path.substringAfterLast('/')
    fun parent(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i <= 0) (if (i == 0) "/" else "") else path.substring(0, i)
    }

    fun join(dir: String, name: String): String = when {
        dir.isEmpty() -> name
        dir.endsWith("/") -> dir + name
        else -> "$dir/$name"
    }

    /** "a.tar.gz" -> "a.tar" + ".gz"; a leading dot or no dot means no extension. */
    fun splitExtension(name: String): Pair<String, String> {
        val i = name.lastIndexOf('.')
        return if (i <= 0) name to "" else name.substring(0, i) to name.substring(i)
    }
}

/** Reads everything (small files only: manifests, journals). */
internal fun FileSystem.readAllBytes(path: String): ByteArray {
    val src = openRead(path)
    try {
        var out = ByteArray(0)
        val buf = ByteArray(8192)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            out += buf.copyOf(n)
        }
        return out
    } finally {
        src.close()
    }
}

/** Streaming SHA-256 of a file; the same hash U8a's ingest planner uses for its verify step. */
fun FileSystem.sha256Hex(path: String, chunk: Int = 64 * 1024): String {
    val h = Sha256()
    val src = openRead(path)
    try {
        val buf = ByteArray(chunk)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            h.update(buf, 0, n)
        }
    } finally {
        src.close()
    }
    return h.digestHex()
}

fun interface Clock {
    fun nowMs(): Long
}
