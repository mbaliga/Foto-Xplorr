package com.fotoxplorr.app.sources.previews

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The persistent preview files of the folders and drives the person added, OUTSIDE Coil's LRU
 * (PHASE-3-DESIGN 1.6: Coil's disk cache is not an offline thumbnail store). Plain files under
 * [root] (the app's no-backup files directory in the app), `root/<sourceRow>/<asset>-<revision>.webp`,
 * plus a small `index.tsv` that remembers when each was last shown.
 *
 * - The size of a source is the sum of its files' lengths, kept in a [PreviewLedger], so reading
 *   it costs nothing and [sizes] can feed the storage panel.
 * - A write goes to a `.tmp` file and is renamed into place, so a crash leaves either the old file
 *   or the new one, never half of one; stray `.tmp` files are removed the next time the folder is read.
 * - The directory is the truth, the index only adds access times: a lost or damaged index costs
 *   the eviction order, never a preview, and a file the ledger does not list is adopted.
 * - Only files this class wrote (names [PreviewPolicy.parseName] accepts, the index, `.tmp`) are
 *   ever deleted. Nothing outside [root] is touched: no user file, no Coil file.
 * - Every call runs on [io]; none is meant for the main thread.
 *
 * [capOf] is the source's cap in bytes (0 = off), asked on every write, so a changed cap applies at once.
 */
class SourcePreviewStore(
    private val root: File,
    private val capOf: (sourceRow: Long) -> Long = { PreviewPolicy.DEFAULT_CAP_BYTES },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val ledgers = HashMap<Long, PreviewLedger>()
    private val sizeState = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Bytes stored per source row, for the sources whose folder has been read at least once this process. */
    val sizes: StateFlow<Map<Long, Long>> = sizeState.asStateFlow()

    private fun dir(sourceRow: Long) = File(root, sourceRow.toString())

    // Caller holds [lock].
    private fun ledger(sourceRow: Long): PreviewLedger = ledgers.getOrPut(sourceRow) { load(sourceRow) }.also { publish(sourceRow, it) }

    private fun publish(sourceRow: Long, l: PreviewLedger) {
        if (sizeState.value[sourceRow] != l.totalBytes) sizeState.update { it + (sourceRow to l.totalBytes) }
    }

    private fun load(sourceRow: Long): PreviewLedger {
        val d = dir(sourceRow)
        val files = d.listFiles() ?: return PreviewLedger()
        val known = runCatching { File(d, INDEX).takeIf { it.isFile }?.readText() }.getOrNull()
            ?.let { PreviewLedger.decode(it) }.orEmpty().associateBy { it.assetRow to it.revision }
        val best = HashMap<Long, PreviewEntry>()
        for (f in files) {
            if (f.name.endsWith(TMP)) { f.delete(); continue }
            val (row, rev) = PreviewPolicy.parseName(f.name) ?: continue
            val len = f.length()
            if (len <= 0L) { f.delete(); continue }
            val e = PreviewEntry(row, rev, len, known[row to rev]?.lastAccessMs ?: f.lastModified())
            val other = best[row]
            if (other == null || other.revision < rev) {
                if (other != null) File(d, PreviewPolicy.fileName(row, other.revision)).delete()
                best[row] = e
            } else {
                f.delete()
            }
        }
        return PreviewLedger(best.values)
    }

    private fun saveIndex(sourceRow: Long, l: PreviewLedger) {
        val d = dir(sourceRow)
        if (l.count == 0) { File(d, INDEX).delete(); return }
        runCatching { atomicWrite(File(d, INDEX), l.encode().toByteArray()) }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("no parent")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.name}")
        val tmp = File(dir, target.name + TMP)
        try {
            tmp.outputStream().use { it.write(bytes); it.flush(); runCatching { it.fd.sync() } }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Whether a preview of exactly this revision is stored. Cheap after the source's folder has been read once. */
    suspend fun has(sourceRow: Long, assetRow: Long, revision: Long): Boolean = withContext(io) {
        synchronized(lock) { ledger(sourceRow).get(assetRow)?.revision == revision }
    }

    /** The stored bytes, or null if there are none for this revision. A hit counts as a use for eviction. */
    suspend fun read(sourceRow: Long, assetRow: Long, revision: Long): ByteArray? = withContext(io) {
        synchronized(lock) {
            val l = ledger(sourceRow)
            val e = l.get(assetRow)
            if (e == null || e.revision != revision) return@synchronized null
            val f = File(dir(sourceRow), PreviewPolicy.fileName(assetRow, revision))
            val bytes = runCatching { f.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                // The file vanished or cannot be read: forget the entry so the next tile makes a new one.
                l.remove(assetRow); f.delete(); publish(sourceRow, l); saveIndex(sourceRow, l)
                return@synchronized null
            }
            l.touch(assetRow, clock())
            bytes
        }
    }

    /** Drops a preview that was read but could not be decoded, so it is created again. */
    suspend fun discard(sourceRow: Long, assetRow: Long): Unit = withContext(io) {
        synchronized(lock) {
            val l = ledger(sourceRow)
            val old = l.remove(assetRow) ?: return@synchronized
            File(dir(sourceRow), PreviewPolicy.fileName(assetRow, old.revision)).delete()
            publish(sourceRow, l); saveIndex(sourceRow, l)
        }
    }

    /**
     * Stores [bytes] as the preview of [assetRow] at [revision], replacing an older revision, then
     * evicts least recently used previews until the source is within its cap. False (and nothing
     * written) when the cap is off or the write failed; a failed write leaves what was there.
     */
    suspend fun write(sourceRow: Long, assetRow: Long, revision: Long, bytes: ByteArray): Boolean = withContext(io) {
        if (bytes.isEmpty() || PreviewPolicy.isOff(capOf(sourceRow))) return@withContext false
        synchronized(lock) {
            val l = ledger(sourceRow)
            val d = dir(sourceRow)
            val target = File(d, PreviewPolicy.fileName(assetRow, revision))
            try {
                atomicWrite(target, bytes)
            } catch (_: IOException) {
                return@synchronized false
            }
            l.put(PreviewEntry(assetRow, revision, bytes.size.toLong(), clock()))?.let { old ->
                if (old.revision != revision) File(d, PreviewPolicy.fileName(assetRow, old.revision)).delete()
            }
            evict(sourceRow, l, capOf(sourceRow), keep = assetRow)
            publish(sourceRow, l); saveIndex(sourceRow, l)
            true
        }
    }

    // Caller holds [lock].
    private fun evict(sourceRow: Long, l: PreviewLedger, cap: Long, keep: Long?) {
        for (row in PreviewPolicy.evictionPlan(l.entries, cap, keep)) {
            val old = l.remove(row) ?: continue
            File(dir(sourceRow), PreviewPolicy.fileName(row, old.revision)).delete()
        }
    }

    /** Applies the source's current cap now (the person lowered it, or switched previews off). */
    suspend fun enforceCap(sourceRow: Long): Unit = withContext(io) {
        synchronized(lock) {
            val l = ledger(sourceRow)
            evict(sourceRow, l, capOf(sourceRow), keep = null)
            publish(sourceRow, l); saveIndex(sourceRow, l)
        }
    }

    /** Bytes the source's previews take, exactly the sum of its files. */
    suspend fun sizeBytes(sourceRow: Long): Long = withContext(io) { synchronized(lock) { ledger(sourceRow).totalBytes } }

    suspend fun count(sourceRow: Long): Int = withContext(io) { synchronized(lock) { ledger(sourceRow).count } }

    /**
     * Deletes the source's previews: its `.webp` files, its index and leftovers, then its folder if
     * that is now empty. Anything else found in there is left alone. Returns the bytes freed.
     */
    suspend fun clear(sourceRow: Long): Long = withContext(io) {
        synchronized(lock) {
            val l = ledger(sourceRow)
            val freed = l.totalBytes
            val d = dir(sourceRow)
            d.listFiles()?.forEach { f ->
                if (f.isFile && (PreviewPolicy.parseName(f.name) != null || f.name == INDEX || f.name.endsWith(TMP))) f.delete()
            }
            d.delete() // only succeeds when empty
            l.clear()
            publish(sourceRow, l)
            freed
        }
    }

    /** A removed source takes its previews with it. Same rules as [clear]. */
    suspend fun forget(sourceRow: Long) {
        clear(sourceRow)
        synchronized(lock) { ledgers.remove(sourceRow); sizeState.update { it - sourceRow } }
    }

    private companion object {
        const val INDEX = "index.tsv"
        const val TMP = ".tmp"
    }
}
