package com.fotoxplorr.core.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR-013 point 5 / MASTER-PLAN Phase 8 WP8.1: the `LINUX_DIR` [Source] -- a plain directory tree
 * on a Linux (or any POSIX) filesystem, used by the Ubuntu Touch shell, the Linux desktop shell
 * and, later, any other platform with ordinary folders.
 *
 * The walk itself is pure Kotlin so it is testable on the JVM and under `linuxX64Test`; the only
 * platform-specific piece is [DirectoryLister], which the host supplies (the `:ut:bridge` module
 * ships a `platform.posix` one). Nothing here touches the network.
 *
 * Behaviour, chosen to keep TRAPS #1/#8 (never sweep on an incomplete walk) true:
 * - Breadth-first, names sorted, so a pass is deterministic and resumable-in-principle.
 * - Dot-files and dot-directories are skipped (`.thumbnails`, `.cache`, `.Trash-1000`, ...).
 * - Symbolic links are neither followed nor listed: a link cycle can never hang a walk, and a
 *   link can never smuggle a file from outside the root into the library.
 * - Only files whose extension [LocalDirMime] recognises as image/video are emitted.
 * - A directory that cannot be listed makes the pass `fullyEnumerated = false` (so no
 *   mark-and-sweep), but the pass still completes; an unreadable *root* is a [SourceEvent.Failed].
 * - Always a full enumeration: [since] is ignored and the token is `null`. Delta passes (inotify,
 *   mtime watermark) are future work and would have to say `fullyEnumerated` honestly.
 * - Pixel size is not read here (width/height = 0) and no EXIF is parsed: `dateTakenMs` is the
 *   file's mtime until the metadata pass fills it. Both are stated, not hidden.
 */
class LocalDirSource(
    private val rootPath: String,
    private val lister: DirectoryLister,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) : Source {
    override val capabilities = SourceCapabilities(canOpen = true, canThumbnail = false)

    private val root: String = rootPath.trimEnd('/').ifEmpty { "/" }
    private val rootName: String = root.substringAfterLast('/').ifEmpty { "root" }

    override fun enumerate(since: SyncToken?): Flow<SourceEvent> = flow {
        if (lister.list(root) == null) {
            emit(SourceEvent.Failed(IllegalStateException("cannot read directory: $root")))
            return@flow
        }
        var complete = true
        var scanned = 0
        // (absolute dir path, path relative to root without leading slash, depth)
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue.addLast(Triple(root, "", 0))
        while (queue.isNotEmpty()) {
            val (dir, rel, depth) = queue.removeFirst()
            val entries = lister.list(dir)
            if (entries == null) {
                complete = false
                continue
            }
            for (entry in entries.sortedBy { it.name }) {
                if (entry.name.startsWith(".") || entry.isSymlink) continue
                val childAbs = if (dir == "/") "/${entry.name}" else "$dir/${entry.name}"
                if (entry.isDirectory) {
                    if (depth + 1 > maxDepth) {
                        complete = false
                    } else {
                        queue.addLast(Triple(childAbs, if (rel.isEmpty()) entry.name else "$rel/${entry.name}", depth + 1))
                    }
                    continue
                }
                val mime = LocalDirMime.forName(entry.name) ?: continue
                val relativePath = if (rel.isEmpty()) rootName else "$rootName/$rel"
                emit(
                    SourceEvent.ItemFound(
                        SourceItem(
                            locator = childAbs,
                            displayName = entry.name,
                            mime = mime,
                            sizeBytes = entry.sizeBytes,
                            width = 0,
                            height = 0,
                            durationMs = 0,
                            dateTakenMs = entry.mtimeMs,
                            dateModifiedMs = entry.mtimeMs,
                            dateAddedMs = null,
                            relativePath = relativePath,
                            bucketId = null,
                            bucketName = relativePath.substringAfterLast('/'),
                            contentUri = "file://" + percentEncodePath(childAbs),
                            trashed = false,
                            generationModified = null,
                        ),
                    ),
                )
                scanned++
                if (scanned % PROGRESS_EVERY == 0) emit(SourceEvent.Progress(scanned))
            }
        }
        emit(SourceEvent.Completed(token = null, fullyEnumerated = complete))
    }

    override suspend fun open(locator: String): SourceHandle = SourceHandle("file://" + percentEncodePath(locator))

    override suspend fun thumbnail(locator: String, size: Int): SourceHandle? = null

    companion object {
        const val DEFAULT_MAX_DEPTH = 32
        private const val PROGRESS_EVERY = 200
    }
}

/** What a [DirectoryLister] reports for one directory entry. [mtimeMs] is milliseconds since the epoch. */
data class DirEntry(
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val sizeBytes: Long = 0,
    val mtimeMs: Long = 0,
)

/** The one platform seam of [LocalDirSource]: list a directory's entries, `null` when unreadable.
 *  Must report a symbolic link as [DirEntry.isSymlink] (lstat semantics), never follow it. */
fun interface DirectoryLister {
    fun list(path: String): List<DirEntry>?
}

/** Extension to mime for the image and video types the walk emits. Deliberately a closed list:
 *  an unknown extension is not media and is skipped, not guessed at. */
object LocalDirMime {
    private val table: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "jxl" to "image/jxl",
        "tif" to "image/tiff", "tiff" to "image/tiff", "svg" to "image/svg+xml",
        "dng" to "image/x-adobe-dng", "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef", "arw" to "image/x-sony-arw", "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2", "raf" to "image/x-fuji-raf",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
    )

    fun forName(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return table[fileName.substring(dot + 1).lowercase()]
    }
}

/** RFC 3986 percent-encoding of a filesystem path for a `file://` URI; `/` and unreserved
 *  characters pass through, every other UTF-8 byte becomes `%XX`. */
internal fun percentEncodePath(path: String): String {
    val sb = StringBuilder()
    for (b in path.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch == '/' || ch == '-' || ch == '.' || ch == '_' || ch == '~')) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append("0123456789ABCDEF"[c shr 4])
            sb.append("0123456789ABCDEF"[c and 0xF])
        }
    }
    return sb.toString()
}
