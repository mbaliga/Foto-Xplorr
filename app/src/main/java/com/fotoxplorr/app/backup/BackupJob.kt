package com.fotoxplorr.app.backup

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Why one file did not make it into this run's packs. It is tried again next time. */
enum class CopyFailure {
    /** The file could not be opened or read (deleted since the scan, permission withdrawn, bad sector). */
    UNREADABLE,

    /** Its length was not what it said when the record started: it changed while it was being copied. */
    CHANGED_WHILE_BACKING_UP,

    /** It grew past what a pack may hold since the scan. */
    TOO_LARGE_NOW,
}

class FailedCopy(val candidate: BackupCandidate, val reason: CopyFailure)

/** How far a run is. [bytesTotal] is the plan's bytes to copy, so [fraction] is of the photos, not of the framing. */
data class BackupProgress(val bytesDone: Long, val bytesTotal: Long, val packNumber: Int, val packCount: Int) {
    val fraction: Float get() = if (bytesTotal <= 0) 1f else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
}

class BackupOutcome(
    val filesCopied: Int,
    val bytesCopied: Long,
    val packsWritten: Int,
    val alreadyBackedUp: Int,
    val failed: List<FailedCopy>,
    val tooLarge: List<SkippedFile>,
    val generation: Int,
    /** Packs whose size the folder reported differently from what Fotoz wrote (a slow provider, or damage). */
    val sizeMismatches: Int,
) {
    /** True when the person should be told something stays undone; the finished message then goes to a dialog, not a toast. */
    val needsAttention: Boolean get() = failed.isNotEmpty() || tooLarge.isNotEmpty() || sizeMismatches > 0
}

/** A failure with a sentence for the person; the job's result carries it to the message dialog. */
class BackupRefused(message: String) : Exception(message)

/**
 * Writes one run of the encrypted backup (ADR-014): the planned packs, an encrypted index beside
 * each, then a new manifest. Everything is added; nothing already in the folder is changed or
 * removed. The only deletions are of the files THIS run created and could not finish.
 *
 * Resumability lives in the folder. A pack is written whole, then its index; a run that dies
 * between two packs leaves finished packs with indexes, which the next run reads
 * ([BackupSetLoader]) instead of copying them again. A pack with no index was never finished and
 * is a leftover. No file name, path or count is written anywhere on the phone.
 *
 * A run that is cancelled or fails after at least one finished pack still writes a manifest for
 * what it finished (best effort, under [NonCancellable]), so the folder is a restorable set at
 * every point rather than only after a perfect run.
 *
 * The job holds [keys] for the run and closes them when it ends, however it ends. Blocking calls
 * go through [io]; long loops call `ensureActive()`, and a [CancellationException] is never
 * turned into a failure (TRAPS #49).
 *
 * @param sections the extra data the manifest carries (tags, locks, pins, edits); read once, at
 *   the start, so a failure there stops the run before a byte is written.
 */
class BackupJob(
    private val destination: BackupDestination,
    private val source: BackupSource,
    private val keys: BackupKeys,
    private val appVersion: String,
    private val sections: suspend () -> List<ManifestSection> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(preparation: BackupPreparation, onProgress: (BackupProgress) -> Unit = {}): BackupOutcome =
        withContext(io) {
            try {
                execute(preparation, onProgress)
            } finally {
                keys.close()
            }
        }

    private suspend fun execute(prep: BackupPreparation, onProgress: (BackupProgress) -> Unit): BackupOutcome {
        val plan = prep.plan
        val extraSections = sections()

        val written = ArrayList<ManifestEntry>()
        val newPacks = ArrayList<ManifestPack>()
        val failed = ArrayList<FailedCopy>()
        var sizeMismatches = 0
        val total = plan.bytesToCopy
        val gate = ProgressGate(onProgress, clock)
        var done = 0L
        var problem: Throwable? = null

        try {
            for ((position, pack) in plan.packs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val packNumber = position + 1
                val result = writePack(pack, prep) { bytes ->
                    done += bytes
                    gate.report(BackupProgress(done, total, packNumber, plan.packs.size))
                }
                failed += result.failed
                // Whatever the pack's files did not use of their planned share still counts as passed.
                done += (pack.copies.sumOf { it.candidate.sizeBytes } - result.bytesStreamed).coerceAtLeast(0)
                if (result.pack != null) {
                    written += result.entries
                    newPacks += result.pack
                    if (result.sizeMismatch) sizeMismatches++
                }
                gate.report(BackupProgress(done, total, packNumber, plan.packs.size), force = true)
            }
        } catch (t: Throwable) {
            problem = t
        }

        val finished = problem == null
        if (finished || written.isNotEmpty()) {
            try {
                withContext(NonCancellable) { writeManifest(prep, plan.nextEntries(written), newPacks, extraSections) }
            } catch (t: Throwable) {
                // The packs and their indexes are still in the folder, so the next run recovers them;
                // but a run that finished must say its snapshot was not written.
                if (problem == null) problem = t
            }
        }

        val outcome = BackupOutcome(
            filesCopied = written.size,
            bytesCopied = written.sumOf { it.sizeBytes },
            packsWritten = newPacks.size,
            alreadyBackedUp = plan.unchanged.size + plan.moved.size,
            failed = failed,
            tooLarge = plan.skippedFor(SkipReason.TOO_LARGE_FOR_PACK),
            generation = prep.nextGeneration,
            sizeMismatches = sizeMismatches,
        )
        problem?.let { throw translate(it, outcome) }
        return outcome
    }

    private class PackResult(
        val pack: ManifestPack?,
        val entries: List<ManifestEntry>,
        val failed: List<FailedCopy>,
        val bytesStreamed: Long,
        val sizeMismatch: Boolean,
    )

    private suspend fun writePack(planned: PlannedPack, prep: BackupPreparation, onBytes: (Long) -> Unit): PackResult {
        val limits = prep.limits
        val sink = destination.create(planned.name)
        var stream: OutputStream? = null
        try {
            val out = try {
                BackupStreams.encrypt(sink, BackupKind.PACK, keys, limits.segmentSize)
            } catch (e: Throwable) {
                runCatching { sink.close() }
                throw e
            }
            stream = out
            val buffer = ByteArray(COPY_BUFFER)
            val zeros = ByteArray(COPY_BUFFER)
            var plaintext = 0L
            var streamed = 0L
            val entries = ArrayList<ManifestEntry>()
            val failed = ArrayList<FailedCopy>()
            for (copy in planned.copies) {
                currentCoroutineContext().ensureActive()
                when (val result = copyOne(copy, out, plaintext, limits, buffer, zeros) { streamed += it; onBytes(it) }) {
                    is CopyResult.Written -> {
                        entries += result.entry
                        plaintext += result.recordBytes
                    }
                    is CopyResult.Skipped -> {
                        plaintext += result.recordBytes
                        failed += FailedCopy(copy.candidate, result.reason)
                    }
                }
            }
            out.close()
            stream = null

            if (entries.isEmpty()) {
                // Nothing in it is worth keeping, and with no index it could never be used.
                withContext(NonCancellable) { destination.delete(planned.name) }
                return PackResult(null, emptyList(), failed, streamed, false)
            }

            val fileBytes = BackupStreams.fileSize(plaintext, limits.segmentSize)
            val record = ManifestPack(planned.name, fileBytes, entries.size)
            writeIndex(record, entries, limits)
            val reported = try {
                destination.sizeOf(planned.name)
            } catch (e: IOException) {
                -1L
            }
            return PackResult(record, entries, failed, streamed, reported >= 0 && reported != fileBytes)
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                runCatching { stream?.close() }
                destination.delete(planned.name)
            }
            throw t
        }
    }

    private sealed interface CopyResult {
        val recordBytes: Long

        class Written(val entry: ManifestEntry, override val recordBytes: Long) : CopyResult

        class Skipped(val reason: CopyFailure, override val recordBytes: Long) : CopyResult
    }

    /**
     * One file as a pack record: `u64 id, u64 size, bytes, sha256` ([PackRecord]). The size is the
     * file's length as the provider reports it now, so a file edited since the scan is copied as it
     * is. If the bytes then do not add up to that length the record is still completed (zero padding)
     * so the pack stays well-formed, and the file is reported rather than indexed.
     */
    private suspend fun copyOne(
        copy: PlannedCopy,
        out: OutputStream,
        packBytes: Long,
        limits: PackLimits,
        buffer: ByteArray,
        zeros: ByteArray,
        onBytes: (Long) -> Unit,
    ): CopyResult {
        val candidate = copy.candidate
        val opened: OpenedSource = openOrNull(candidate) ?: return CopyResult.Skipped(CopyFailure.UNREADABLE, 0)
        opened.use {
            val size = if (opened.length >= 0) opened.length else candidate.sizeBytes
            val recordBytes = PackRecord.bytesFor(size)
            if (limits.fileBytes(packBytes + recordBytes) > limits.hardCapBytes) {
                return CopyResult.Skipped(CopyFailure.TOO_LARGE_NOW, 0)
            }
            out.write(PackRecord.encodeHeader(copy.entryId, size))
            val digest = MessageDigest.getInstance("SHA-256")
            val quick = QuickHashCollector()
            var remaining = size
            var failure: CopyFailure? = null
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val n = try {
                    opened.input.read(buffer, 0, wanted)
                } catch (e: IOException) {
                    failure = CopyFailure.UNREADABLE
                    break
                }
                if (n < 0) {
                    failure = CopyFailure.CHANGED_WHILE_BACKING_UP
                    break
                }
                out.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                quick.update(buffer, 0, n)
                remaining -= n
                onBytes(n.toLong())
            }
            if (failure == null) {
                val extra = try {
                    opened.input.read()
                } catch (e: IOException) {
                    -1
                }
                if (extra >= 0) failure = CopyFailure.CHANGED_WHILE_BACKING_UP
            }
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val n = minOf(zeros.size.toLong(), remaining).toInt()
                out.write(zeros, 0, n)
                digest.update(zeros, 0, n)
                remaining -= n
            }
            val sha = digest.digest()
            out.write(sha)
            if (failure != null) return CopyResult.Skipped(failure, recordBytes)
            val entry = copy.toEntry(hex(sha), opened.originalLocation, quick.result()).copy(sizeBytes = size)
            return CopyResult.Written(entry, recordBytes)
        }
    }

    private fun openOrNull(candidate: BackupCandidate): OpenedSource? = try {
        source.open(candidate)
    } catch (e: IOException) {
        null
    }

    private fun writeIndex(pack: ManifestPack, entries: List<ManifestEntry>, limits: PackLimits) {
        val name = BackupLayout.indexName(BackupLayout.packNumber(pack.name)!!)
        val sink = destination.create(name)
        try {
            val out = BackupStreams.encrypt(sink, BackupKind.PACK_INDEX, keys, limits.segmentSize)
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), WRITER_BUFFER).use { PackIndexWriter(it).write(pack, entries) }
        } catch (t: Throwable) {
            runCatching { sink.close() }
            destination.delete(name)
            throw t
        }
    }

    private fun writeManifest(
        prep: BackupPreparation,
        entries: List<ManifestEntry>,
        newPacks: List<ManifestPack>,
        extra: List<ManifestSection>,
    ) {
        val limits = prep.limits
        val known = LinkedHashMap<String, ManifestPack>()
        prep.loaded.manifest?.packs?.forEach { known[it.name] = it }
        prep.loaded.recovered.forEach { known[it.pack.name] = it.pack }
        newPacks.forEach { known[it.name] = it }
        val referenced = entries.mapTo(HashSet()) { it.packName }
        val packs = known.values.filter { it.name in referenced }.sortedBy { it.name }

        val name = BackupLayout.manifestName(prep.nextGeneration)
        val sink = destination.create(name)
        try {
            val out = BackupStreams.encrypt(sink, BackupKind.MANIFEST, keys, limits.segmentSize)
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), WRITER_BUFFER).use { writer ->
                val manifest = ManifestWriter(writer)
                manifest.writeHeader(ManifestHeader(prep.nextGeneration, clock(), appVersion, keys.iterations, limits.segmentSize))
                entries.sortedBy { it.entryId }.forEach(manifest::writeEntry)
                packs.forEach(manifest::writePack)
                extra.forEach(manifest::writeSection)
                manifest.finish()
            }
        } catch (t: Throwable) {
            runCatching { sink.close() }
            destination.delete(name)
            throw t
        }
    }

    /** What the person is told when the run stopped early. Cancellation passes through untouched. */
    private fun translate(problem: Throwable, partial: BackupOutcome): Throwable {
        if (problem is CancellationException || problem is BackupRefused) return problem
        val saved = if (partial.packsWritten == 0) {
            ""
        } else {
            " ${BackupCopy.count(partial.packsWritten, "pack")} (${BackupCopy.size(partial.bytesCopied)}) were saved before it stopped."
        }
        val carryOn = " Run the backup again with the same folder and passphrase and it carries on where it stopped."
        return when {
            problem is IOException && DestinationErrors.isNoSpace(problem) ->
                BackupRefused("The backup folder is full.$saved Free some space.$carryOn")
            problem is IOException ->
                BackupRefused("The backup folder could not be written to.$saved Check that the card or drive is still connected.$carryOn")
            else -> problem
        }
    }

    private companion object {
        const val COPY_BUFFER = 256 * 1024
        const val WRITER_BUFFER = 1 shl 16

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** Passes a progress value on only when it moved enough to matter, so a notification is not rebuilt per buffer. */
internal class ProgressGate(
    private val sink: (BackupProgress) -> Unit,
    private val clock: () -> Long,
) {
    private var lastFraction = -1f
    private var lastAt = 0L

    fun report(progress: BackupProgress, force: Boolean = false) {
        val now = clock()
        val moved = progress.fraction - lastFraction
        if (!force && lastFraction >= 0 && moved < MIN_STEP && now - lastAt < MIN_INTERVAL_MILLIS) return
        lastFraction = progress.fraction
        lastAt = now
        sink(progress)
    }

    private companion object {
        const val MIN_STEP = 0.005f
        const val MIN_INTERVAL_MILLIS = 1000L
    }
}
