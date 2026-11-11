package com.fotoxplorr.core.organize.fileops

import kotlin.concurrent.Volatile
import kotlinx.datetime.TimeZone

enum class StepStatus { DONE, SKIPPED, FAILED, CANCELLED, NOT_RUN }

/**
 * The per-step result, keyed by the ORIGINAL's path ([source]) so the UI can show it next to the file.
 * [target] is the final path actually used (a numbered name after keep-both). For a failed cross-source
 * move whose copy already verified, [target] is set and the source was kept.
 */
data class StepResult(
    val stepId: String,
    val index: Int,
    val source: String,
    val target: String?,
    val status: StepStatus,
    val error: FileOpsError? = null,
    val message: String? = null,
    val bytes: Long = 0L,
    val sha256: String? = null,
    /** This result came from the journal of an earlier, interrupted run. */
    val resumed: Boolean = false,
    /** The existing file a REPLACE sent to the trash, restorable by this id. */
    val replacedTrashId: String? = null,
    /** Where the original went (cross-source move or Trash). */
    val trashId: String? = null,
)

data class RunReport(
    val planId: String,
    val results: List<StepResult>,
    val cancelled: Boolean,
    /** Set when the run stopped early (drive gone, no space, journal unwritable); the rest are NOT_RUN and resumable. */
    val abortedBy: FileOpsError?,
    val bytesCopied: Long,
) {
    val completed: Boolean get() = results.all { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
    val failures: List<StepResult> get() = results.filter { it.status == StepStatus.FAILED }
}

data class Progress(
    val stepIndex: Int,
    val stepCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentPath: String,
)

/** Cooperative cancellation, checked between chunks and between steps. */
class CancelToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }
}

/**
 * Executes a [FileOpsPlan] (ADR-022). Invariants, each pinned by a test:
 *  - nothing ever appears under a final name except by renaming a fully written and verified temp file;
 *  - an original is never overwritten, and never removed except into a source's `.fotoz-trash`, and only
 *    after the copy verified (cross-source move) or on an explicit Trash step;
 *  - REPLACE needs the consent flag and trashes the existing file first;
 *  - an interrupted run resumes from its [Journal]: finished steps are skipped, dangling temp files are
 *    deleted, a verified-but-not-yet-trashed move continues at the trashing.
 *
 * Exceptions that are not [FileOpsException] (a process death in a test) are deliberately not caught:
 * a real crash cleans up nothing, which is exactly what resume must cope with.
 */
class FileOpsEngine(
    private val fs: FileSystem,
    private val journal: Journal,
    private val clock: Clock,
    private val zone: TimeZone = TimeZone.UTC,
    private val chunkSize: Int = 64 * 1024,
) {
    private enum class Side { SOURCE, DESTINATION }

    private class StepCtx {
        var target: String? = null
        var sha: String? = null
        var bytes: Long = 0L
        var replacedTrashId: String? = null
        var trashId: String? = null
        var resumed: Boolean = false
    }

    private sealed interface Resolved {
        data class Use(val target: String, val replacedTrashId: String?) : Resolved
        data object Skip : Resolved
    }

    fun run(
        plan: FileOpsPlan,
        cancel: CancelToken = CancelToken(),
        prompt: ConflictPrompt? = null,
        onProgress: (Progress) -> Unit = {},
    ): RunReport {
        val state = journal.load(plan.id)
        for (t in state.danglingTemps()) {
            // Best effort: a temp left by a crash is ours by name and never under a final name.
            try {
                if (fs.stat(t) != null) fs.delete(t)
            } catch (e: FileOpsException) {
                // The drive may still be unplugged; the copy step deletes a stale temp before writing anyway.
            }
        }
        val resolver = ConflictResolver(plan.conflicts, prompt)
        val results = ArrayList<StepResult>(plan.steps.size)
        var bytesDone = 0L
        var copied = 0L
        var aborted: FileOpsError? = null
        var cancelled = false

        for ((index, step) in plan.steps.withIndex()) {
            if (aborted != null || cancelled || cancel.isCancelled) {
                if (cancel.isCancelled) cancelled = true
                results += StepResult(step.id, index, step.source, null, if (cancelled) StepStatus.CANCELLED else StepStatus.NOT_RUN)
                continue
            }
            val done = state.terminal(step.id)
            if (done != null) {
                bytesDone += step.bytes
                results += StepResult(step.id, index, step.source, done.target, done.status, resumed = true, trashId = done.trashId)
                continue
            }
            val ctx = StepCtx()
            val stepStartBytes = bytesDone
            try {
                val r = execute(plan, step, index, state, resolver, cancel, ctx) { n ->
                    bytesDone += n
                    onProgress(Progress(index, plan.steps.size, bytesDone, plan.estimatedBytes, step.source))
                }
                if (r.status == StepStatus.DONE) copied += ctx.bytes
                bytesDone = stepStartBytes + step.bytes
                results += r
            } catch (e: FileOpsException) {
                bytesDone = stepStartBytes
                val status = if (e.error == FileOpsError.CANCELLED) StepStatus.CANCELLED else StepStatus.FAILED
                if (status == StepStatus.CANCELLED) cancelled = true
                if (e.error in ABORTING) aborted = e.error
                if (e.error != FileOpsError.JOURNAL_UNWRITABLE) {
                    try {
                        journal.append(plan.id, JournalEntry.Finished(step.id, status, ctx.target, e.error, ctx.trashId))
                    } catch (j: FileOpsException) {
                        aborted = FileOpsError.JOURNAL_UNWRITABLE
                    }
                }
                results += StepResult(
                    step.id, index, step.source, ctx.target, status, e.error, e.message, 0L, ctx.sha,
                    replacedTrashId = ctx.replacedTrashId, trashId = ctx.trashId,
                )
            }
        }
        return RunReport(plan.id, results, cancelled, aborted, copied)
    }

    /** Forget a finished plan's journal (the caller decides when; a finished journal makes a re-run a no-op). */
    fun discardJournal(plan: FileOpsPlan) = journal.clear(plan.id)

    // ---- steps ----

    private fun execute(
        plan: FileOpsPlan,
        step: PlanStep,
        index: Int,
        state: JournalState,
        resolver: ConflictResolver,
        cancel: CancelToken,
        ctx: StepCtx,
        onBytes: (Long) -> Unit,
    ): StepResult = when (step.kind) {
        StepKind.TRASH -> trashStep(plan, step, index, ctx)
        StepKind.RENAME, StepKind.MOVE_NATIVE -> renameStep(plan, step, index, state, resolver, ctx)
        StepKind.COPY, StepKind.MOVE_CROSS -> transferStep(plan, step, index, state, resolver, cancel, ctx, onBytes)
    }

    private fun trashStep(plan: FileOpsPlan, step: PlanStep, index: Int, ctx: StepCtx): StepResult {
        val trashId = "${plan.id}-${step.id}"
        requireFile(step.source)
        j(plan, JournalEntry.Started(step.id, trashId, null))
        TrashStore(fs, fs.sourceRoot(step.source), clock, zone).add(step.source, trashId)
        ctx.trashId = trashId
        j(plan, JournalEntry.Finished(step.id, StepStatus.DONE, null, null, trashId))
        return StepResult(step.id, index, step.source, null, StepStatus.DONE, trashId = trashId)
    }

    private fun renameStep(
        plan: FileOpsPlan, step: PlanStep, index: Int, state: JournalState, resolver: ConflictResolver, ctx: StepCtx,
    ): StepResult {
        val desired = step.target!!
        if (desired == step.source) return skipped(plan, step, index, desired, "Name unchanged.")
        val src = fs.stat(step.source)
        if (src == null) {
            // Resume: the rename may have happened right before the crash.
            val st = state.started(step.id)
            val t = st?.target?.let { fs.stat(it) }
            if (st != null && t != null && (step.sourceSize == null || t.size == step.sourceSize)) {
                ctx.target = st.target
                ctx.resumed = true
                j(plan, JournalEntry.Finished(step.id, StepStatus.DONE, st.target, null, null))
                return StepResult(step.id, index, step.source, st.target, StepStatus.DONE, resumed = true)
            }
            throw FileOpsException(FileOpsError.SOURCE_GONE, "The file is gone.")
        }
        if (src.isDirectory) throw FileOpsException(FileOpsError.UNSUPPORTED, "Folders are not handled.")
        val resolved = resolveTarget(plan, step, desired, state, resolver, ctx)
        if (resolved is Resolved.Skip) return skipped(plan, step, index, desired, "Name taken, skipped.")
        resolved as Resolved.Use
        fs.mkdirs(FilePaths.parent(resolved.target))
        j(plan, JournalEntry.Started(step.id, resolved.target, null))
        ctx.target = resolved.target
        fs.rename(step.source, resolved.target)
        j(plan, JournalEntry.Finished(step.id, StepStatus.DONE, resolved.target, null, null))
        return StepResult(step.id, index, step.source, resolved.target, StepStatus.DONE, replacedTrashId = resolved.replacedTrashId)
    }

    private fun transferStep(
        plan: FileOpsPlan, step: PlanStep, index: Int, state: JournalState, resolver: ConflictResolver,
        cancel: CancelToken, ctx: StepCtx, onBytes: (Long) -> Unit,
    ): StepResult {
        var prior = state.copied(step.id)
        if (prior != null && fs.stat(prior.target)?.size != prior.size) prior = null // the copy is not there any more: redo
        var resumed = false
        val target: String
        val sha: String
        val size: Long
        var replaced: String? = state.trashedId(step.id)

        if (prior != null) {
            target = prior.target; sha = prior.sha256; size = prior.size; resumed = true
            ctx.target = target; ctx.sha = sha; ctx.bytes = size
        } else {
            val srcStat = fs.stat(step.source) ?: throw FileOpsException(FileOpsError.SOURCE_GONE, "The original is gone.")
            if (srcStat.isDirectory) throw FileOpsException(FileOpsError.UNSUPPORTED, "Folders are not handled.")
            val desired = step.target!!
            fs.mkdirs(FilePaths.parent(desired))

            val adopted = adoptIfIdentical(state.started(step.id), step.source, srcStat)
            if (adopted != null) {
                target = adopted.first; sha = adopted.second; size = srcStat.size; resumed = true
                ctx.target = target; ctx.sha = sha; ctx.bytes = size
                j(plan, JournalEntry.Copied(step.id, target, size, sha))
            } else {
                val resolved = resolveTarget(plan, step, desired, state, resolver, ctx)
                if (resolved is Resolved.Skip) return skipped(plan, step, index, desired, "Name taken, skipped.")
                resolved as Resolved.Use
                target = resolved.target
                replaced = resolved.replacedTrashId
                ctx.target = target
                val free = fs.freeBytes(target)
                if (free != null && free < srcStat.size) {
                    throw FileOpsException(FileOpsError.NO_SPACE, "Not enough space on the destination.")
                }
                val temp = target + PART
                if (fs.stat(temp) != null) fs.delete(temp) // stale temp from an earlier run
                j(plan, JournalEntry.Started(step.id, target, temp))
                val (n, h) = streamCopy(step.source, temp, srcStat, cancel, onBytes)
                size = n; sha = h
                try {
                    fs.rename(temp, target)
                } catch (e: FileOpsException) {
                    bestEffortDelete(temp)
                    throw mapSide(e, Side.DESTINATION)
                }
                ctx.sha = sha; ctx.bytes = size
                j(plan, JournalEntry.Copied(step.id, target, size, sha))
            }
        }
        ctx.replacedTrashId = replaced

        if (step.kind == StepKind.COPY) {
            j(plan, JournalEntry.Finished(step.id, StepStatus.DONE, target, null, null))
            return StepResult(step.id, index, step.source, target, StepStatus.DONE, bytes = size, sha256 = sha, resumed = resumed, replacedTrashId = replaced)
        }

        // MOVE_CROSS: the copy is verified and renamed into place. Only now may the original go, and only into its trash.
        val still = fs.stat(step.source)
        val trashId = "${plan.id}-${step.id}-s"
        if (still != null) {
            if (resumed && side(Side.SOURCE) { fs.sha256Hex(step.source, chunkSize) } != sha) {
                throw FileOpsException(FileOpsError.VERIFY_MISMATCH, "The original changed; it was kept.")
            }
            if (still.size != size) throw FileOpsException(FileOpsError.VERIFY_MISMATCH, "The original changed; it was kept.")
            TrashStore(fs, fs.sourceRoot(step.source), clock, zone).add(step.source, trashId)
            ctx.trashId = trashId
        } else if (resumed) {
            // Crash after the original reached the trash but before the journal heard of it.
            val store = TrashStore(fs, fs.sourceRoot(step.source), clock, zone)
            if (store.list().any { it.id == trashId }) ctx.trashId = trashId
        }
        j(plan, JournalEntry.Finished(step.id, StepStatus.DONE, target, null, ctx.trashId))
        return StepResult(
            step.id, index, step.source, target, StepStatus.DONE, bytes = size, sha256 = sha, resumed = resumed,
            replacedTrashId = replaced, trashId = ctx.trashId,
        )
    }

    /** After a crash between the rename and the Copied entry: the target may already be a complete, identical copy. */
    private fun adoptIfIdentical(started: JournalEntry.Started?, source: String, srcStat: FileStat): Pair<String, String>? {
        val t = started?.target ?: return null
        val ts = fs.stat(t) ?: return null
        if (ts.size != srcStat.size) return null
        val a = side(Side.SOURCE) { fs.sha256Hex(source, chunkSize) }
        val b = side(Side.DESTINATION) { fs.sha256Hex(t, chunkSize) }
        return if (a == b) t to a else null
    }

    private fun resolveTarget(
        plan: FileOpsPlan, step: PlanStep, desired: String, state: JournalState, resolver: ConflictResolver, ctx: StepCtx,
    ): Resolved {
        val existing = side(Side.DESTINATION) { fs.stat(desired) }
        if (existing == null) {
            // A crash after the existing file went to the trash but before the journal heard of it: the id is derived, so find it.
            val rid = "${plan.id}-${step.id}-r"
            val earlier = state.trashedId(step.id)
                ?: if (plan.conflicts.policy == ConflictPolicy.REPLACE || plan.conflicts.policy == ConflictPolicy.ASK) {
                    side(Side.DESTINATION) {
                        if (TrashStore(fs, fs.sourceRoot(desired), clock, zone).list().any { it.id == rid }) rid else null
                    }
                } else {
                    null
                }
            ctx.replacedTrashId = earlier
            return Resolved.Use(desired, earlier)
        }
        return when (resolver.resolve(Conflict(step.id, step.source, desired))) {
            ConflictChoice.SKIP -> Resolved.Skip
            ConflictChoice.KEEP_BOTH ->
                Resolved.Use(ConflictNaming.keepBoth(desired) { side(Side.DESTINATION) { fs.stat(it) } != null }, null)
            ConflictChoice.REPLACE -> {
                // Never overwrite in place: the existing file goes to the trash so it can be restored.
                val id = "${plan.id}-${step.id}-r"
                side(Side.DESTINATION) { TrashStore(fs, fs.sourceRoot(desired), clock, zone).add(desired, id) }
                j(plan, JournalEntry.Trashed(step.id, id))
                ctx.replacedTrashId = id
                Resolved.Use(desired, id)
            }
        }
    }

    /** Streams [src] to a NEW [temp], hashing as it goes, then verifies by reading the temp back. Returns size and hash. */
    private fun streamCopy(src: String, temp: String, srcStat: FileStat, cancel: CancelToken, onBytes: (Long) -> Unit): Pair<Long, String> {
        var reader: ByteSource? = null
        var sink: ByteSink? = null
        try {
            val r = side(Side.SOURCE) { fs.openRead(src) }
            reader = r
            val w = side(Side.DESTINATION) { fs.openCreate(temp) }
            sink = w
            val hash = Sha256()
            val buf = ByteArray(chunkSize)
            var total = 0L
            while (true) {
                if (cancel.isCancelled) throw FileOpsException(FileOpsError.CANCELLED, "Cancelled.")
                val n = side(Side.SOURCE) { r.read(buf) }
                if (n < 0) break
                hash.update(buf, 0, n)
                side(Side.DESTINATION) { w.write(buf, 0, n) }
                total += n
                onBytes(n.toLong())
            }
            side(Side.DESTINATION) { w.close() }
            sink = null
            r.close()
            reader = null

            val sha = hash.digestHex()
            val tempSize = side(Side.DESTINATION) { fs.stat(temp)?.size }
            val after = side(Side.SOURCE) { fs.stat(src) }
            when {
                tempSize != total -> throw FileOpsException(FileOpsError.VERIFY_MISMATCH, "The copy has the wrong size.")
                after == null || after.size != srcStat.size || after.modifiedMs != srcStat.modifiedMs ->
                    throw FileOpsException(FileOpsError.VERIFY_MISMATCH, "The original changed while copying.")
                side(Side.DESTINATION) { fs.sha256Hex(temp, chunkSize) } != sha ->
                    throw FileOpsException(FileOpsError.VERIFY_MISMATCH, "The copy does not match the original.")
            }
            return total to sha
        } catch (e: FileOpsException) {
            // Only OUR temp name is touched. The source and any existing target are never deleted here.
            try { reader?.close() } catch (ignored: FileOpsException) {}
            try { sink?.close() } catch (ignored: FileOpsException) {}
            bestEffortDelete(temp)
            throw e
        }
    }

    // ---- helpers ----

    private fun requireFile(path: String) {
        val s = side(Side.SOURCE) { fs.stat(path) } ?: throw FileOpsException(FileOpsError.SOURCE_GONE, "The file is gone.")
        if (s.isDirectory) throw FileOpsException(FileOpsError.UNSUPPORTED, "Folders are not handled.")
    }

    private fun skipped(plan: FileOpsPlan, step: PlanStep, index: Int, target: String, why: String): StepResult {
        j(plan, JournalEntry.Finished(step.id, StepStatus.SKIPPED, target, null, null))
        return StepResult(step.id, index, step.source, target, StepStatus.SKIPPED, message = why)
    }

    private fun j(plan: FileOpsPlan, entry: JournalEntry) {
        try {
            journal.append(plan.id, entry)
        } catch (e: FileOpsException) {
            throw FileOpsException(FileOpsError.JOURNAL_UNWRITABLE, "Progress could not be saved.")
        }
    }

    private fun bestEffortDelete(path: String) {
        try {
            if (fs.stat(path) != null) fs.delete(path)
        } catch (ignored: FileOpsException) {
            // Drive gone: the resume sweep removes it.
        }
    }

    private fun <T> side(s: Side, block: () -> T): T = try {
        block()
    } catch (e: FileOpsException) {
        throw mapSide(e, s)
    }

    private fun mapSide(e: FileOpsException, s: Side): FileOpsException = when (e.error) {
        FileOpsError.DEVICE_GONE, FileOpsError.NOT_FOUND ->
            FileOpsException(if (s == Side.SOURCE) FileOpsError.SOURCE_GONE else FileOpsError.DESTINATION_GONE, e.message ?: "")
        else -> e
    }

    private companion object {
        const val PART = ".fotoz-part"
        val ABORTING = setOf(FileOpsError.DESTINATION_GONE, FileOpsError.NO_SPACE, FileOpsError.JOURNAL_UNWRITABLE)
    }
}
