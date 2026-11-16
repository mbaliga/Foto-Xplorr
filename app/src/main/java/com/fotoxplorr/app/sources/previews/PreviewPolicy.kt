package com.fotoxplorr.app.sources.previews

/**
 * The pure rules of the offline previews store (PHASE-3-DESIGN 1.6, WP3.4, owner decision 11):
 * what a preview file is called, what the per-source cap is, and which previews go first when a
 * source is over it. No Android and no file access, so JVM tests pin every rule.
 */
object PreviewPolicy {
    /** One source's budget unless the person changed it: 1 GB, counted the way a drive's label counts (1 KB = 1000 bytes). */
    const val DEFAULT_CAP_BYTES: Long = 1_000_000_000L

    /** The longest edge of a stored preview, in pixels. */
    const val EDGE_PX = 256

    /** WebP quality: 256 px tiles at 80 are about 10 to 25 KB. */
    const val QUALITY = 80

    /** A cap of 0 (or less) switches previews off for the source: nothing is written, what exists is evicted. */
    fun isOff(capBytes: Long): Boolean = capBytes <= 0L

    /** `<assetRow>-<revision>.webp`: the revision is the file's modified time, so an overwritten file gets a new name. */
    fun fileName(assetRow: Long, revision: Long): String = "$assetRow-$revision$EXT"

    /** The inverse of [fileName], or null for any other name (a temp file, the index, something that is not ours). */
    fun parseName(name: String): Pair<Long, Long>? {
        if (!name.endsWith(EXT)) return null
        val stem = name.dropLast(EXT.length)
        // A revision can be negative for a file with a pre-1970 date; the row never is.
        val cut = stem.indexOf('-', startIndex = 1)
        if (cut < 1) return null
        val row = stem.substring(0, cut).toLongOrNull() ?: return null
        val rev = stem.substring(cut + 1).toLongOrNull() ?: return null
        return if (row >= 1) row to rev else null
    }

    /**
     * Which assets lose their preview so that [entries] fit in [capBytes]: least recently used
     * first, oldest name first on a tie (so the plan is deterministic). [keepAssetRow] (the preview
     * just written) is never chosen, so a fresh write is never evicted by its own arrival even when
     * a single preview is larger than the cap. A cap that is off evicts everything.
     */
    fun evictionPlan(entries: Collection<PreviewEntry>, capBytes: Long, keepAssetRow: Long? = null): List<Long> {
        if (isOff(capBytes)) return entries.map { it.assetRow }
        var total = entries.sumOf { it.bytes }
        if (total <= capBytes) return emptyList()
        val plan = ArrayList<Long>()
        val order = entries.filter { it.assetRow != keepAssetRow }.sortedWith(compareBy({ it.lastAccessMs }, { it.assetRow }))
        for (e in order) {
            if (total <= capBytes) break
            plan += e.assetRow
            total -= e.bytes
        }
        return plan
    }

    const val EXT = ".webp"
}

/** One stored preview: the asset it is for, the revision it was made from, its exact size on disk and when it was last shown. */
data class PreviewEntry(val assetRow: Long, val revision: Long, val bytes: Long, val lastAccessMs: Long)

/**
 * The size ledger of one source: one entry per asset (a new revision replaces the old), the total
 * always the sum of the entries, so the cost of a source is exact and costs no file access.
 * Not thread safe; the store serialises access.
 */
class PreviewLedger(initial: Collection<PreviewEntry> = emptyList()) {
    private val byAsset = LinkedHashMap<Long, PreviewEntry>().also { m -> initial.forEach { m[it.assetRow] = it } }

    val entries: Collection<PreviewEntry> get() = byAsset.values
    val count: Int get() = byAsset.size
    var totalBytes: Long = byAsset.values.sumOf { it.bytes }
        private set

    fun get(assetRow: Long): PreviewEntry? = byAsset[assetRow]

    /** Records [entry] and returns the entry it replaced (its file is now stale), if any. */
    fun put(entry: PreviewEntry): PreviewEntry? {
        val old = byAsset.put(entry.assetRow, entry)
        if (old != null) totalBytes -= old.bytes
        totalBytes += entry.bytes
        return old
    }

    fun remove(assetRow: Long): PreviewEntry? {
        val old = byAsset.remove(assetRow) ?: return null
        totalBytes -= old.bytes
        return old
    }

    fun touch(assetRow: Long, nowMs: Long) {
        val e = byAsset[assetRow] ?: return
        byAsset[assetRow] = e.copy(lastAccessMs = nowMs)
    }

    fun clear() {
        byAsset.clear()
        totalBytes = 0L
    }

    /** One line per entry, `row TAB revision TAB bytes TAB lastAccess`. */
    fun encode(): String = buildString {
        for (e in byAsset.values) append(e.assetRow).append('\t').append(e.revision).append('\t').append(e.bytes).append('\t').append(e.lastAccessMs).append('\n')
    }

    companion object {
        /** Reads what [encode] wrote; a line that is not four numbers is skipped, never an error. */
        fun decode(text: String): List<PreviewEntry> = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            val row = p[0].toLongOrNull() ?: return@mapNotNull null
            val rev = p[1].toLongOrNull() ?: return@mapNotNull null
            val bytes = p[2].toLongOrNull() ?: return@mapNotNull null
            val access = p[3].toLongOrNull() ?: return@mapNotNull null
            if (row < 1 || bytes < 0) null else PreviewEntry(row, rev, bytes, access)
        }.toList()
    }
}
