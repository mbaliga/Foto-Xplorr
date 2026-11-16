package com.fotoxplorr.core.organize.ingest

/**
 * @property offerDeleteFromCard OFF by default. When on, the report lists the items whose every
 *   copy verified as delete candidates; nothing is removed until [IngestRunner.deleteFromCard].
 */
data class IngestOptions(val offerDeleteFromCard: Boolean = false)

data class FailedCopy(val itemId: String, val destIndex: Int, val message: String)

/**
 * The summary. [verifiedCopies] counts (item, destination) pairs; [deleteCandidates] is empty
 * unless the option is on, and holds only files with disposition COPY whose EVERY destination copy
 * verified by full hash. Skipped duplicates are never candidates: their twin was not verified by this run.
 */
data class IngestReport(
    val copiedItems: Int,
    val alreadyPresent: Int,
    val verifiedCopies: Int,
    val failed: List<FailedCopy>,
    val skippedInCard: Int,
    val skippedInLibrary: Int,
    val skippedNoName: Int,
    val renamedForConflict: Int,
    val pairs: Int,
    val bytesWritten: Long,
    val deleteCandidates: List<String>,
) {
    val complete: Boolean get() = failed.isEmpty()
}

data class DeleteResult(val deleted: List<String>, val refused: List<String>)

/**
 * Executes a plan: per file, per destination: write, then verify by reading the copy back and
 * comparing size and [Fingerprints.full] with the source. Everything is journalled, so a kill at any
 * point resumes by running again with the same journal: verified copies are not redone; a copy that
 * exists but was not journalled (killed between write and journal line) is accepted only if it
 * verifies, otherwise the file is written under a fresh keep-both name. NOTHING is ever overwritten,
 * and the card is never touched here. Catches [Exception] per copy (a failed file does not stop the
 * import) but not [Error]s.
 *
 * Plan once, then [run]. To resume after a restart re-plan with `prior = journal.plannedPaths()`.
 */
class IngestRunner(
    private val source: IngestSource,
    private val targets: List<IngestTarget>,
    private val journal: IngestJournal,
    /**
     * Names this import (one card, one session). Item ids are only stable within a card, so lines of another
     * scope are never read; pass the same value to [plannedPaths] when re-planning a resume.
     */
    private val scope: String = "",
) {
    /** The journal's latest line per (item, destination), read ONCE and kept current by [append]. */
    private inner class Ledger {
        val latest: MutableMap<String, JournalEntry> = journal.latest(scope)

        fun get(itemId: String, dest: Int): JournalEntry? = latest["$itemId|$dest"]

        fun append(e: JournalEntry) {
            val tagged = if (e.scope == scope) e else e.copy(scope = scope)
            journal.append(tagged)
            latest["${tagged.itemId}|${tagged.destIndex}"] = tagged
        }
    }

    /** A VERIFIED line counts for [f] only when it recorded the same fingerprint key and size (not just the same id). */
    private fun trusted(e: JournalEntry?, f: PlannedFile): Boolean =
        e != null && (e.state == JournalState.VERIFIED || e.state == JournalState.CARD_DELETED) &&
            e.fingerprint == f.fingerprint.key && e.size == f.fingerprint.size

    fun run(plan: IngestPlan, options: IngestOptions = IngestOptions()): IngestReport {
        require(plan.destinationCount == targets.size)
        val ledger = Ledger()
        for (f in plan.toCopy) {
            if (ledger.get(f.item.id, -1)?.relPath != f.relPath) {
                ledger.append(JournalEntry(f.item.id, -1, JournalState.PLANNED, f.relPath))
            }
        }
        var verified = 0
        var written = 0L
        var present = 0
        var renamedExec = 0
        val failed = ArrayList<FailedCopy>()
        val perItemOk = HashMap<String, Int>()

        for (f in plan.toCopy) {
            for (d in targets.indices) {
                val prev = ledger.get(f.item.id, d)
                if (trusted(prev, f)) {
                    verified++; perItemOk[f.item.id] = (perItemOk[f.item.id] ?: 0) + 1; present++
                    continue
                }
                try {
                    val outcome = copyOne(f, d, prev, ledger)
                    when (outcome) {
                        is Outcome.Verified -> {
                            verified++; perItemOk[f.item.id] = (perItemOk[f.item.id] ?: 0) + 1
                            written += outcome.bytesWritten
                            if (outcome.bytesWritten == 0L) present++
                            if (outcome.renamed) renamedExec++
                        }
                        is Outcome.Bad -> failed.add(FailedCopy(f.item.id, d, outcome.why))
                    }
                } catch (e: Exception) {
                    ledger.append(JournalEntry(f.item.id, d, JournalState.FAILED, f.relPath, e.message ?: "error"))
                    failed.add(FailedCopy(f.item.id, d, e.message ?: "error"))
                }
            }
        }
        val okItems = plan.toCopy.filter { perItemOk[it.item.id] == targets.size }
        val candidates = if (options.offerDeleteFromCard && source.canDelete) okItems.map { it.item.id } else emptyList()
        return IngestReport(
            copiedItems = okItems.size,
            alreadyPresent = present,
            verifiedCopies = verified,
            failed = failed,
            skippedInCard = plan.files.count { it.disposition == Disposition.SKIP_DUPLICATE_IN_CARD },
            skippedInLibrary = plan.files.count { it.disposition == Disposition.SKIP_ALREADY_IN_LIBRARY },
            skippedNoName = plan.files.count { it.disposition == Disposition.SKIP_NO_FREE_NAME },
            renamedForConflict = plan.toCopy.count { it.conflictRenamed } + renamedExec,
            pairs = plan.pairCount,
            bytesWritten = written,
            deleteCandidates = candidates,
        )
    }

    private sealed interface Outcome {
        data class Verified(val bytesWritten: Long, val renamed: Boolean) : Outcome
        data class Bad(val why: String) : Outcome
    }

    private fun copyOne(f: PlannedFile, d: Int, prev: JournalEntry?, ledger: Ledger): Outcome {
        val target = targets[d]
        val reader = source.open(f.item)
        try {
            val srcHash = Fingerprints.full(reader)
            val srcSize = reader.size
            // Where did an earlier attempt put it, if any?
            val earlier = if (prev?.state == JournalState.COPIED) prev.relPath else f.relPath
            var path = earlier
            var renamed = false
            if (target.exists(path)) {
                if (matches(target, path, srcSize, srcHash)) {
                    ledger.append(JournalEntry(f.item.id, d, JournalState.VERIFIED, path, "already present", f.fingerprint.key, srcSize))
                    return Outcome.Verified(0, false)
                }
                path = freeName(target, f.relPath)
                renamed = true
            }
            val n = target.write(path, reader)
            ledger.append(JournalEntry(f.item.id, d, JournalState.COPIED, path))
            if (!matches(target, path, srcSize, srcHash)) {
                target.discardOwnUnverified(path)
                ledger.append(JournalEntry(f.item.id, d, JournalState.FAILED, path, "verify mismatch"))
                return Outcome.Bad("The copy did not match the original.")
            }
            ledger.append(JournalEntry(f.item.id, d, JournalState.VERIFIED, path, "", f.fingerprint.key, srcSize))
            return Outcome.Verified(n, renamed)
        } finally {
            reader.close()
        }
    }

    private fun matches(target: IngestTarget, path: String, size: Long, hash: String): Boolean {
        val r = target.open(path) ?: return false
        try {
            return r.size == size && Fingerprints.full(r) == hash
        } finally { r.close() }
    }

    private fun freeName(target: IngestTarget, relPath: String): String {
        val dir = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2..9999) {
            val p = (if (dir.isEmpty()) "" else "$dir/") + "$stem ($n)$ext"
            if (!target.exists(p)) return p
        }
        throw IllegalStateException("No free name for $relPath")
    }

    /**
     * Removes the given items from the card, the one place this package touches the source. Each id
     * is re-checked: it must be a COPY item of [plan], EVERY destination's latest journal line must be a
     * VERIFIED one recorded for this item's fingerprint and size, and right before the delete the card
     * item is re-fingerprinted and every destination copy is re-opened and compared (size and full hash)
     * with the card's CURRENT bytes. Anything else is refused untouched. Call it only after the person
     * confirmed; [IngestOptions.offerDeleteFromCard] must have been on for [run].
     */
    fun deleteFromCard(plan: IngestPlan, ids: List<String>, options: IngestOptions): DeleteResult {
        val deleted = ArrayList<String>()
        val refused = ArrayList<String>()
        val ledger = Ledger()
        val byId = plan.toCopy.associateBy { it.item.id }
        for (id in ids) {
            val f = byId[id]
            val entries = f?.let { targets.indices.map { d -> ledger.get(id, d) } }
            val allVerified = f != null && entries!!.all { trusted(it, f) }
            if (!options.offerDeleteFromCard || !source.canDelete || f == null || !allVerified ||
                !stillIdentical(f, entries!!.map { it!!.relPath })
            ) {
                refused.add(id); continue
            }
            if (source.delete(f.item)) {
                val marked = entries.first()!!
                ledger.append(JournalEntry(id, -1, JournalState.CARD_DELETED, f.relPath, "", marked.fingerprint, marked.size))
                for (d in targets.indices) {
                    val e = entries[d]!!
                    ledger.append(JournalEntry(id, d, JournalState.CARD_DELETED, e.relPath, "", e.fingerprint, e.size))
                }
                deleted.add(id)
            } else refused.add(id)
        }
        return DeleteResult(deleted, refused)
    }

    /** The card item still has the planned fingerprint and every destination copy equals its current bytes. */
    private fun stillIdentical(f: PlannedFile, copyPaths: List<String>): Boolean = try {
        val r = source.open(f.item)
        try {
            if (Fingerprints.cheap(r, f.item.captureMillis).key != f.fingerprint.key) {
                false
            } else {
                val hash = Fingerprints.full(r)
                targets.indices.all { d ->
                    val c = targets[d].open(copyPaths[d])
                    c != null && try { c.size == r.size && Fingerprints.full(c) == hash } finally { c.close() }
                }
            }
        } finally {
            r.close()
        }
    } catch (e: Exception) {
        false
    }
}
