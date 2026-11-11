package com.fotoxplorr.core.organize.fileops

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The app trash for a SAF tree or USB drive (ADR-022 point 4): a VISIBLE `.fotoz-trash/` folder at the
 * source root holding each file under a unique stored name, a `manifest.json` (original path, name,
 * size, time, expiry) and a `.nomedia` so galleries skip it.
 *
 * Nothing here purges by itself. [TrashEntry.expiresAtMs] is arithmetic for the "will be removed on ..."
 * line; the only deleting members are [purge] and [emptyTrash], which the UI calls from an explicit
 * Empty trash tap.
 *
 * Crash order: on add the manifest entry is written BEFORE the file moves, on restore the file moves
 * BEFORE the entry goes, so a crash can leave an entry without its file (pruned on the next write and
 * ignored by [list]) but never a trashed file without a way back.
 */
class TrashStore(
    private val fs: FileSystem,
    private val root: String,
    private val clock: Clock,
    private val zone: TimeZone = TimeZone.UTC,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    val trashDir: String = FilePaths.join(root, TRASH_DIR)
    private val manifestPath = FilePaths.join(trashDir, MANIFEST)
    private val newManifestPath = FilePaths.join(trashDir, "$MANIFEST.new")

    /**
     * Move [path] into the trash. A caller-chosen [id] makes the call idempotent (the engine derives it
     * from the plan, so a resumed run finds its own earlier work); otherwise one is generated.
     */
    fun add(path: String, id: String? = null): TrashEntry {
        val entries = loadRaw()
        val existing = id?.let { want -> entries.firstOrNull { it.id == want } }
        if (existing != null && fs.stat(FilePaths.join(trashDir, existing.storedName)) != null) return existing

        val stat = fs.stat(path) ?: throw FileOpsException(FileOpsError.SOURCE_GONE, "File is gone: $path")
        if (stat.isDirectory) throw FileOpsException(FileOpsError.UNSUPPORTED, "Folders cannot be trashed.")
        fs.mkdirs(trashDir)
        ensureNoMedia()

        val now = clock.nowMs()
        val useId = id ?: freshId(now, entries)
        val name = FilePaths.name(path)
        val entry = TrashEntry(
            id = useId,
            originalPath = path,
            name = name,
            storedName = "$useId-$name",
            size = stat.size,
            trashedAtMs = now,
            expiresAtMs = TrashExpiry.expiresAtMs(now, retentionDays, zone),
        )
        val kept = entries.filter { it.id != useId && fs.stat(FilePaths.join(trashDir, it.storedName)) != null }
        writeManifest(kept + entry)
        fs.rename(path, FilePaths.join(trashDir, entry.storedName))
        return entry
    }

    /** Entries whose stored file is still there, oldest first. */
    fun list(): List<TrashEntry> =
        loadRaw().filter { fs.stat(FilePaths.join(trashDir, it.storedName)) != null }.sortedBy { it.trashedAtMs }

    /** Entries past their expiry. A suggestion for the UI only: nothing is ever purged automatically. */
    fun expired(): List<TrashEntry> {
        val now = clock.nowMs()
        return list().filter { it.expiresAtMs <= now }
    }

    /** Puts the file back; on a clash keeps both (`name (2).ext`). Returns the restored path. */
    fun restore(id: String): String {
        val entries = loadRaw()
        val entry = entries.firstOrNull { it.id == id }
            ?: throw FileOpsException(FileOpsError.NOT_FOUND, "Not in the trash.")
        val stored = FilePaths.join(trashDir, entry.storedName)
        if (fs.stat(stored) == null) throw FileOpsException(FileOpsError.SOURCE_GONE, "The trashed file is gone.")
        fs.mkdirs(FilePaths.parent(entry.originalPath))
        val target = if (fs.stat(entry.originalPath) == null) entry.originalPath
        else ConflictNaming.keepBoth(entry.originalPath) { fs.stat(it) != null }
        fs.rename(stored, target)
        writeManifest(entries.filter { it.id != id })
        return target
    }

    /** Explicit, user-confirmed removal of ONE trashed file. */
    fun purge(id: String) {
        val entries = loadRaw()
        val entry = entries.firstOrNull { it.id == id } ?: return
        val stored = FilePaths.join(trashDir, entry.storedName)
        if (fs.stat(stored) != null) fs.delete(stored)
        writeManifest(entries.filter { it.id != id })
    }

    /** Explicit Empty trash. Returns how many files were removed. */
    fun emptyTrash(): Int {
        val entries = loadRaw()
        var n = 0
        for (e in entries) {
            val stored = FilePaths.join(trashDir, e.storedName)
            if (fs.stat(stored) != null) {
                fs.delete(stored)
                n++
            }
        }
        writeManifest(emptyList())
        return n
    }

    private fun freshId(now: Long, entries: List<TrashEntry>): String {
        var n = 0
        while (true) {
            val id = "t$now-$n"
            if (entries.none { it.id == id } && fs.stat(FilePaths.join(trashDir, id)) == null) return id
            n++
        }
    }

    private fun ensureNoMedia() {
        val p = FilePaths.join(trashDir, ".nomedia")
        if (fs.stat(p) == null) {
            try {
                fs.openCreate(p).close()
            } catch (e: FileOpsException) {
                if (e.error != FileOpsError.EXISTS) throw e
            }
        }
    }

    private fun loadRaw(): List<TrashEntry> {
        if (fs.stat(manifestPath) != null) return parseManifest(manifestPath)
        if (fs.stat(newManifestPath) == null) return emptyList()
        // No manifest.json: either we died between deleting the old one and renaming the complete .new (read it),
        // or this is the very first manifest and the .new write was torn (nothing was ever recorded: empty).
        return try {
            parseManifest(newManifestPath)
        } catch (e: FileOpsException) {
            emptyList()
        }
    }

    private fun parseManifest(path: String): List<TrashEntry> {
        try {
            val root = MiniJson.parse(fs.readAllBytes(path).decodeToString()) as Map<*, *>
            return (root["entries"] as List<*>).map { TrashEntry.fromMap(it as Map<*, *>) }
        } catch (e: FileOpsException) {
            throw e
        } catch (e: Exception) {
            // Never treat an unreadable manifest as empty: the next write would erase the way back.
            throw FileOpsException(FileOpsError.IO, "The trash manifest is unreadable.")
        }
    }

    private fun writeManifest(entries: List<TrashEntry>) {
        fs.mkdirs(trashDir)
        val text = buildString {
            append("{\"version\":1,\"entries\":[")
            entries.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append(e.toJson())
            }
            append("]}")
        }
        if (fs.stat(newManifestPath) != null) fs.delete(newManifestPath)
        val sink = fs.openCreate(newManifestPath)
        try {
            val bytes = text.encodeToByteArray()
            sink.write(bytes, 0, bytes.size)
        } finally {
            sink.close()
        }
        // The old manifest is only deleted once the complete new one exists; loadRaw reads .new if we die in between.
        if (fs.stat(manifestPath) != null) fs.delete(manifestPath)
        fs.rename(newManifestPath, manifestPath)
    }

    companion object {
        const val TRASH_DIR = ".fotoz-trash"
        const val MANIFEST = "manifest.json"
        const val DEFAULT_RETENTION_DAYS = 30
    }
}

data class TrashEntry(
    val id: String,
    val originalPath: String,
    val name: String,
    val storedName: String,
    val size: Long,
    val trashedAtMs: Long,
    /** When the "will be removed on" line points to. Nothing acts on it automatically. */
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    internal fun toJson(): String =
        "{\"id\":${MiniJson.quote(id)},\"originalPath\":${MiniJson.quote(originalPath)},\"name\":${MiniJson.quote(name)}," +
            "\"storedName\":${MiniJson.quote(storedName)},\"size\":$size,\"trashedAtMs\":$trashedAtMs,\"expiresAtMs\":$expiresAtMs}"

    internal companion object {
        fun fromMap(m: Map<*, *>) = TrashEntry(
            id = m["id"] as String,
            originalPath = m["originalPath"] as String,
            name = m["name"] as String,
            storedName = m["storedName"] as String,
            size = m["size"] as Long,
            trashedAtMs = m["trashedAtMs"] as Long,
            expiresAtMs = m["expiresAtMs"] as Long,
        )
    }
}

object TrashExpiry {
    /** [days] calendar days after [trashedAtMs] in [zone], so DST and year boundaries keep the same wall-clock time. */
    fun expiresAtMs(trashedAtMs: Long, days: Int, zone: TimeZone): Long =
        Instant.fromEpochMilliseconds(trashedAtMs).plus(days, DateTimeUnit.DAY, zone).toEpochMilliseconds()

    fun daysLeft(entry: TrashEntry, nowMs: Long): Int {
        val left = entry.expiresAtMs - nowMs
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    private const val DAY_MS = 86_400_000L
}
