package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import java.io.BufferedInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What a restore puts back. */
enum class RestoreScope {
    /** The tags-and-collections file inside the backup, matched to the photos already on the phone. No photo is written. */
    TAGS_ONLY,

    /** Photos and videos, then the tags, folder locks, map pins and edits that go with them. */
    EVERYTHING,
}

/** A new file being written, not yet visible to anything else on the phone. */
interface PendingRestoreFile {
    val output: OutputStream

    /** Makes the file real and returns where it ended up. Closes [output]. */
    fun commit(): CommittedFile

    /** Throws the half-written file away. Safe to call after a failure and more than once. */
    fun abort()
}

/** [mediaId] is the NEW MediaStore id; [dateModifiedSeconds] is the revision a restored edit recipe belongs to. */
class CommittedFile(val mediaId: Long, val dateModifiedSeconds: Long)

/**
 * The phone side of a restore: what is already here and where new files are written. The real
 * one is `MediaStoreRestoreTarget`; the tests use an in-memory one. Every call blocks.
 */
interface RestoreTarget {
    /** False before Android 10, where a file's folder cannot be chosen ([ProtectedPlacement]). */
    val canChooseFolder: Boolean

    /** Every photo and video on the phone that is not in the bin. */
    fun localFiles(): List<RestoreLocalFile>

    /** SHA-256 of [file] as the backup would have hashed it, or null when it cannot be read. */
    fun sha256Of(file: RestoreLocalFile): String?

    /** The folder keys that are locked on this phone right now. */
    fun lockedFolders(): Set<String>

    /** A new pending file for [item]; [IOException] (or a runtime exception) if the phone refuses it. */
    fun begin(item: RestoreItem): PendingRestoreFile
}

class LockResult(val added: Int, val kept: Int)

/** An edit recipe made ready for a restored photo: its new id and the revision of the file it now belongs to. */
class RestoredRecipe(val mediaId: Long, val dateModifiedSeconds: Long, val recipe: JsonValue)

/** The phone's stores a restore adds to. Every method is additive: nothing it holds is replaced or removed. */
interface RestoreApplier {
    /** Merges the tags, collections, captions, favourites, sensitive marks and archive marks. */
    suspend fun applyMetadata(prepared: MetadataBackup.PreparedRestore)

    suspend fun applyLocks(locks: List<BackupSections.Lock>): LockResult

    /**
     * Marks photos sensitive. Called while the files are still being written, as soon as a sensitive
     * photo has its new id, so it is hidden from the moment the gallery can see it and not only
     * when the rest of the tags arrive at the end. The same marks are merged again later.
     */
    suspend fun markSensitive(ids: Set<Long>)

    /** Returns how many pins were new; a photo that already has a hand-placed pin keeps it. */
    suspend fun applyPins(pins: List<BackupSections.Pin>): Int

    /** Returns how many recipes were new; a photo that already has one keeps it. */
    suspend fun applyRecipes(recipes: List<RestoredRecipe>): Int

    /**
     * Asks for a rescan and waits, for a bounded time, until the app's catalogue lists [ids]. A
     * pin written for a photo the catalogue does not know yet can be swept away by the next
     * clean-up of the location index.
     */
    suspend fun awaitCatalogue(ids: Set<Long>)
}

enum class RestoreFailure {
    /** The phone would not create the file (a name or folder it rejects, a permission). */
    CANNOT_CREATE,

    /** The phone could not write it (storage trouble other than full). */
    CANNOT_WRITE,

    /** The bytes did not match the checksum stored with them, or the pack stopped authenticating. */
    DAMAGED,

    /** The readable part of its pack does not contain it. */
    NOT_IN_PACK,
}

class FailedRestore(val entry: ManifestEntry, val reason: RestoreFailure)

/** The decisions of a restore, made and shown before anything is written. */
class RestorePreparation(
    val scope: RestoreScope,
    val set: RestoreSet,
    /** The files to write, skip and report; empty for [RestoreScope.TAGS_ONLY]. */
    val plan: RestorePlan,
    val arranged: ProtectedPlacement.Arranged?,
    /** This phone's photos by id, read once; the identity fallback for ids the backup's records do not settle. */
    val catalogue: Map<Long, MediaIdentity>,
    val lockedHere: Set<String>,
    /** The revision (`DATE_MODIFIED`, seconds) of each local photo, which a restored edit recipe is stored against. */
    val localRevisions: Map<Long, Long>,
    /** Backup media id to this phone's id, for what is settled before writing (already here, or matched by identity). */
    val settled: Map<Long, Long>,
    /** The tags file as it would be applied; null when the backup has none or it is unusable. */
    val metadata: MetadataBackup.PreparedRestore?,
) {
    val counts: RestoreCounts = RestoreCounts(
        toRestore = plan.count(RestoreAction.RESTORE),
        asCopies = plan.count(RestoreAction.RESTORE_AS_COPY),
        alreadyHere = plan.count(RestoreAction.SKIP_ALREADY_HERE),
        missing = plan.count(RestoreAction.SKIP_MISSING),
        unsupported = plan.count(RestoreAction.SKIP_UNSUPPORTED),
        heldBack = plan.count(RestoreAction.SKIP_NEEDS_FOLDER),
        bytesToWrite = plan.bytesToWrite,
        matched = if (scope == RestoreScope.TAGS_ONLY) settled.size else 0,
        notOnPhone = if (scope == RestoreScope.TAGS_ONLY) set.entries.count { it.mediaId !in settled } else 0,
    )

    /**
     * True when running this would change nothing: no file to write, no tags to merge, no pin or
     * saved edit to put back. The review card then offers no button.
     */
    val isNoOp: Boolean
        get() {
            val tags = metadata?.summary?.nothingToRestore ?: true
            return when (scope) {
                RestoreScope.TAGS_ONLY -> tags
                RestoreScope.EVERYTHING -> counts.toWrite == 0 && tags && set.info.pins == 0 && set.info.recipes == 0
            }
        }

    /** Folder locks that will be applied: only folders a protected photo is actually written into. */
    val locksToApply: List<BackupSections.Lock>
        get() {
            val arranged = arranged ?: return emptyList()
            val written = plan.toWrite.mapTo(HashSet()) { ProtectedPlacement.lockKey(it.targetRelativePath) }
            return arranged.locks.filter { it.folderKey in written }
        }
}

/** The numbers on the review card and, with [RestoreOutcome], on the result. */
class RestoreCounts(
    val toRestore: Int,
    /** Files that find a different file under their name and come back as `name (restored).ext`. */
    val asCopies: Int,
    val alreadyHere: Int,
    val missing: Int,
    val unsupported: Int,
    val heldBack: Int,
    val bytesToWrite: Long,
    /** For a tags-only restore: photos found on the phone, and photos the backup knows that are not. */
    val matched: Int,
    val notOnPhone: Int,
) {
    val toWrite: Int get() = toRestore + asCopies
    val skipped: Int get() = unsupported + heldBack
}

class RestoreOutcome(
    val scope: RestoreScope,
    val restored: Int,
    val restoredAsCopy: Int,
    val alreadyHere: Int,
    val missing: Int,
    val skipped: Int,
    val failed: List<FailedRestore>,
    val bytesWritten: Long,
    /** Tags and the like applied (or, for a failure of that step, null with [metadataFailed]). */
    val metadata: MetadataBackup.RestoreSummary?,
    val metadataFailed: Boolean,
    val locks: LockResult?,
    val pins: Int,
    val recipes: Int,
    val protectedWithoutLock: Int,
    val protectedRelocated: Int,
    /** Tags-only: photos the backup knows that this phone does not have. */
    val notOnPhone: Int,
) {
    val needsAttention: Boolean
        get() = failed.isNotEmpty() || missing > 0 || skipped > 0 || metadataFailed || protectedWithoutLock > 0 ||
            (metadata != null && metadata.photosMissing > 0)
}

/**
 * Works out what a restore would do, writing nothing. The expensive part is hashing the few local
 * files that could be a match ([RestorePlanner.candidatesToHash]); everything else is arithmetic.
 */
object RestorePreparer {
    suspend fun prepare(
        set: RestoreSet,
        scope: RestoreScope,
        target: RestoreTarget,
        onProgress: (Float) -> Unit = {},
        io: CoroutineDispatcher = Dispatchers.IO,
    ): RestorePreparation = withContext(io) {
        val local = target.localFiles()
        val catalogue = local.associate { it.mediaId to it.identity }
        val lockedHere = target.lockedFolders()
        val revisions = local.associate { it.mediaId to it.dateModifiedSeconds }
        when (scope) {
            RestoreScope.TAGS_ONLY -> {
                val settled = matchByIdentity(set.entries, catalogue)
                RestorePreparation(
                    scope = scope,
                    set = set,
                    plan = RestorePlan(emptyList()),
                    arranged = null,
                    catalogue = catalogue,
                    lockedHere = lockedHere,
                    localRevisions = revisions,
                    settled = settled,
                    metadata = RestoreMetadata.prepare(set, settled, catalogue),
                )
            }
            RestoreScope.EVERYTHING -> {
                val arranged = ProtectedPlacement.arrange(
                    entries = set.entries,
                    locks = BackupSections.locks(set.section(BackupSections.LOCKS)),
                    canChooseFolder = target.canChooseFolder,
                    lockedHere = lockedHere,
                    inUse = local.mapTo(HashSet()) { ProtectedPlacement.lockKey(it.relativePath) },
                )
                val candidates = RestorePlanner.candidatesToHash(arranged.entries, local)
                val hashes = HashMap<Long, String>()
                for ((position, file) in candidates.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    target.sha256Of(file)?.let { hashes[file.mediaId] = it }
                    onProgress((position + 1).toFloat() / candidates.size)
                }
                val planned = RestorePlanner.plan(arranged.entries, local, hashes)
                val held = arranged.heldBack.map {
                    RestoreItem(it, RestoreAction.SKIP_NEEDS_FOLDER, RestorePlanner.kindOf(it.mimeType), "", "", null)
                }
                val plan = RestorePlan(planned.items + held)
                // What the backup's records already settle: files that are here. The ones about to be
                // written are assumed to land under their own id for the preview's counts only.
                val settled = HashMap<Long, Long>(plan.alreadyHereIdMap())
                plan.toWrite.forEach { settled.putIfAbsent(it.entry.mediaId, it.entry.mediaId) }
                RestorePreparation(
                    scope = scope,
                    set = set,
                    plan = plan,
                    arranged = arranged,
                    catalogue = catalogue,
                    lockedHere = lockedHere,
                    localRevisions = revisions,
                    settled = settled,
                    metadata = RestoreMetadata.prepare(set, settled, catalogue),
                )
            }
        }
    }

    /** Backup media id to this phone's id, by name, size and date, for photos the backup knows and the phone has. */
    fun matchByIdentity(entries: List<ManifestEntry>, catalogue: Map<Long, MediaIdentity>): Map<Long, Long> {
        val wanted = LinkedHashMap<Long, MediaIdentity>()
        for (entry in entries.sortedBy { it.entryId }) {
            wanted.putIfAbsent(entry.mediaId, MediaIdentity(entry.displayName, entry.sizeBytes, entry.dateTakenMillis, entry.relativePath.ifEmpty { null }))
        }
        return IdRemap.matchIdentities(wanted, catalogue).oldToNew
    }
}

/**
 * Runs a prepared restore: the files first, then what goes with them (ADR-014).
 *
 * Additive and idempotent. Nothing on the phone is overwritten or removed: a name already taken
 * gives a `(restored)` copy, bytes already here are skipped, and every store is merged into. A run
 * that dies at any point is repeated from the start and finishes the job, because a file written
 * last time is "already here" this time and its tags are merged again.
 *
 * Order matters in two places. Folder locks are applied BEFORE the first file is written, so a
 * protected photo is never on the phone without its lock, even for a moment or after a crash. And
 * tags, pins and edits are applied after the files, once every new id is known.
 *
 * A file is written to a pending entry and made real only when its bytes arrived whole and their
 * SHA-256 equals the one the backup recorded. A damaged pack fails the files it can no longer
 * deliver and the run goes on with the next pack. The cipher layer (`BackupStreams`) has already
 * authenticated every byte before this sees it. Cancellation is never turned into a failure
 * (TRAPS #49) and a file in flight is thrown away.
 *
 * Takes over [keys] and closes them when it ends, however it ends.
 */
class RestoreJob(
    private val destination: BackupDestination,
    private val keys: BackupKeys,
    private val target: RestoreTarget,
    private val applier: RestoreApplier,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(prep: RestorePreparation, onProgress: (Float) -> Unit = {}): RestoreOutcome =
        withContext(io) {
            try {
                execute(prep, onProgress)
            } finally {
                keys.close()
            }
        }

    private companion object {
        const val MARK_GROUP = 25
    }

    private class Progress(private val total: Long, private val sink: (Float) -> Unit) {
        private var done = 0L
        private var last = -1f

        fun add(bytes: Long) {
            done += bytes
            val fraction = if (total <= 0) 0f else (done.toDouble() / total).coerceIn(0.0, 0.99).toFloat()
            if (fraction - last >= 0.005f || last < 0) {
                last = fraction
                sink(fraction)
            }
        }
    }

    private class Written(val entry: ManifestEntry, val committed: CommittedFile)

    /** Sensitive photos wait here for a moment so the marks are written in groups, not one preferences write each. */
    private inner class SensitiveMarks {
        private val waiting = LinkedHashSet<Long>()
        var failed = false
            private set

        suspend fun add(id: Long) {
            waiting += id
            if (waiting.size >= MARK_GROUP) flush()
        }

        suspend fun flush() {
            if (waiting.isEmpty()) return
            val ids = waiting.toSet()
            waiting.clear()
            attempt { applier.markSensitive(ids) }.onFailure { failed = true }
        }
    }

    private suspend fun execute(prep: RestorePreparation, onProgress: (Float) -> Unit): RestoreOutcome {
        if (prep.scope == RestoreScope.TAGS_ONLY) return tagsOnly(prep, onProgress)

        val written = ArrayList<Written>()
        val failed = ArrayList<FailedRestore>()
        var bytesWritten = 0L
        var problem: Throwable? = null
        var locks: LockResult? = null
        val progress = Progress(prep.plan.bytesToWrite, onProgress)
        val marks = SensitiveMarks()

        try {
            val toApply = prep.locksToApply
            if (toApply.isNotEmpty()) locks = applier.applyLocks(toApply)
            val byPack = prep.plan.toWrite.groupBy { it.entry.packName }.toSortedMap()
            for ((packName, items) in byPack) {
                currentCoroutineContext().ensureActive()
                bytesWritten += restorePack(packName, items, written, failed, progress, marks)
                marks.flush()
            }
        } catch (t: Throwable) {
            problem = t
            withContext(NonCancellable) { marks.flush() }
        }
        if (problem != null) throw translate(problem, prep, written.size)

        currentCoroutineContext().ensureActive()
        val settled = LinkedHashMap<Long, Long>(prep.plan.alreadyHereIdMap())
        for (w in written.sortedBy { it.entry.entryId }) settled.putIfAbsent(w.entry.mediaId, w.committed.mediaId)

        var applied: MetadataBackup.RestoreSummary? = null
        var metadataFailed = marks.failed
        val prepared = RestoreMetadata.prepare(prep.set, settled, prep.catalogue)
        if (prepared != null) {
            val result = attempt { applier.applyMetadata(prepared) }
            if (result.isSuccess) applied = prepared.summary else metadataFailed = true
        }

        var pins = 0
        var recipes = 0
        if (written.isNotEmpty()) attempt { applier.awaitCatalogue(written.mapTo(HashSet()) { it.committed.mediaId }) }
        attempt { pins = applier.applyPins(remappedPins(prep.set, settled)) }.onFailure { metadataFailed = true }
        attempt { recipes = applier.applyRecipes(remappedRecipes(prep, written)) }.onFailure { metadataFailed = true }

        onProgress(1f)
        val actionOf = prep.plan.items.associate { it.entry.entryId to it.action }
        return RestoreOutcome(
            scope = prep.scope,
            restored = written.count { actionOf.getValue(it.entry.entryId) == RestoreAction.RESTORE },
            restoredAsCopy = written.count { actionOf.getValue(it.entry.entryId) == RestoreAction.RESTORE_AS_COPY },
            alreadyHere = prep.counts.alreadyHere,
            missing = prep.counts.missing,
            skipped = prep.counts.skipped,
            failed = failed,
            bytesWritten = bytesWritten,
            metadata = applied,
            metadataFailed = metadataFailed,
            locks = locks,
            pins = pins,
            recipes = recipes,
            protectedWithoutLock = prep.arranged?.withoutLock ?: 0,
            protectedRelocated = prep.arranged?.relocated ?: 0,
            notOnPhone = 0,
        )
    }

    private suspend fun tagsOnly(prep: RestorePreparation, onProgress: (Float) -> Unit): RestoreOutcome {
        var applied: MetadataBackup.RestoreSummary? = null
        var failedStep = false
        val prepared = RestoreMetadata.prepare(prep.set, prep.settled, prep.catalogue)
        if (prepared != null) {
            val result = attempt { applier.applyMetadata(prepared) }
            if (result.isSuccess) applied = prepared.summary else failedStep = true
        }
        onProgress(1f)
        return RestoreOutcome(
            scope = prep.scope,
            restored = 0,
            restoredAsCopy = 0,
            alreadyHere = 0,
            missing = 0,
            skipped = 0,
            failed = emptyList(),
            bytesWritten = 0,
            metadata = applied,
            metadataFailed = failedStep,
            locks = null,
            pins = 0,
            recipes = 0,
            protectedWithoutLock = 0,
            protectedRelocated = 0,
            notOnPhone = prep.counts.notOnPhone,
        )
    }

    /** Streams one pack, writing the files [items] want from it. Returns the bytes written. */
    private suspend fun restorePack(
        packName: String,
        items: List<RestoreItem>,
        written: MutableList<Written>,
        failed: MutableList<FailedRestore>,
        progress: Progress,
        marks: SensitiveMarks,
    ): Long {
        val wanted = items.associateBy { it.entry.entryId }
        val remaining = HashSet(wanted.keys)
        var damage: BackupException? = null
        var bytes = 0L
        try {
            BufferedInputStream(destination.open(packName), 1 shl 16).use { raw ->
                val opened = BackupStreams.open(raw, keys, BackupKind.PACK)
                val reader = PackReader(opened.plaintext)
                while (remaining.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val header = reader.next() ?: break
                    val item = wanted[header.entryId]?.takeIf { header.entryId in remaining }
                    if (item == null) {
                        // An older version of a changed file, or a file this restore does not want.
                        reader.consume(null)
                        continue
                    }
                    val done = copyOne(item, header, reader, progress)
                    remaining -= header.entryId
                    when (done) {
                        is OneFile.Ok -> {
                            written += Written(item.entry, done.committed)
                            bytes += item.entry.sizeBytes
                            if (EntryFlag.WAS_SENSITIVE in item.entry.flags) marks.add(done.committed.mediaId)
                        }
                        is OneFile.Failed -> failed += FailedRestore(item.entry, done.reason)
                    }
                }
            }
        } catch (e: BackupException) {
            damage = e
        }
        for (id in remaining) {
            failed += FailedRestore(wanted.getValue(id).entry, if (damage != null) RestoreFailure.DAMAGED else RestoreFailure.NOT_IN_PACK)
        }
        return bytes
    }

    private sealed interface OneFile {
        class Ok(val committed: CommittedFile) : OneFile

        class Failed(val reason: RestoreFailure) : OneFile
    }

    private suspend fun copyOne(item: RestoreItem, header: PackReader.Header, reader: PackReader, progress: Progress): OneFile {
        val entry = item.entry
        if (header.size != entry.sizeBytes) {
            reader.consume(null)
            return OneFile.Failed(RestoreFailure.DAMAGED)
        }
        val pending = try {
            target.begin(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reader.consume(null)
            return OneFile.Failed(RestoreFailure.CANNOT_CREATE)
        }
        val guard = WriteGuard(pending.output) { progress.add(it) }
        var committed = false
        try {
            val digest = try {
                reader.consume(guard)
            } catch (e: IOException) {
                // A failure that did not come from the phone's storage is the pack's or its folder's
                // (the caller sorts those out, after the file below is thrown away).
                if (guard.failure == null || e is BackupException) throw e
                throw StorageFull(e)
            }
            guard.failure?.let { return OneFile.Failed(RestoreFailure.CANNOT_WRITE) }
            if (!digest.trailerMatches || digest.sha256 != entry.sha256) return OneFile.Failed(RestoreFailure.DAMAGED)
            val file = try {
                pending.commit()
            } catch (e: IOException) {
                if (DestinationErrors.isNoSpace(e)) throw StorageFull(e)
                return OneFile.Failed(RestoreFailure.CANNOT_WRITE)
            } catch (e: SecurityException) {
                return OneFile.Failed(RestoreFailure.CANNOT_WRITE)
            }
            committed = true
            return OneFile.Ok(file)
        } finally {
            if (!committed) withContext(NonCancellable) { runCatching { pending.abort() } }
        }
    }

    /** The phone's storage is full: every later file would fail the same way, so the run ends. */
    private class StorageFull(cause: Throwable) : IOException("The phone's storage is full", cause)

    /**
     * Counts what reaches the phone and remembers the first write failure. A full disk is passed
     * on at once (the run ends). Any other failure is held and the rest of the record is read and
     * dropped, so the pack reader stays in step and the next file can still be restored.
     */
    private class WriteGuard(out: OutputStream, private val onBytes: (Long) -> Unit) : FilterOutputStream(out) {
        var failure: IOException? = null
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failure != null) return
            try {
                out.write(b, off, len)
                onBytes(len.toLong())
            } catch (e: IOException) {
                failure = e
                if (DestinationErrors.isNoSpace(e)) throw e
            }
        }

        // The pending file closes its own output when it is committed or thrown away.
        override fun close() = Unit
    }

    private fun remappedPins(set: RestoreSet, settled: Map<Long, Long>): List<BackupSections.Pin> {
        val photos = BackupPhotos(set.entries)
        return BackupSections.pins(set.section(BackupSections.PINS)).mapNotNull { pin ->
            val backupId = photos.idOf(pin.mediaId, pin.identity) ?: return@mapNotNull null
            settled[backupId]?.let { BackupSections.Pin(it, pin.latitude, pin.longitude) }
        }
    }

    /**
     * The backup's own id for the photo a pin or recipe record is about. A record that says who it
     * is (name, size, date taken) is matched to the entry of that photo, which a merge of two
     * phones' snapshots may have given another id than the one the record carries; one that does
     * not is the id as written. A photo the backup has no file for, or two it cannot tell apart, is null.
     */
    private class BackupPhotos(entries: List<ManifestEntry>) {
        private val byNameAndSize = HashMap<Pair<String, Long>, MutableList<Pair<Long, MediaIdentity>>>()

        init {
            for (entry in entries) {
                val who = MediaIdentity(entry.displayName, entry.sizeBytes, entry.dateTakenMillis, entry.relativePath.ifEmpty { null })
                byNameAndSize.getOrPut(entry.displayName to entry.sizeBytes) { ArrayList() } += entry.mediaId to who
            }
        }

        fun idOf(id: Long, identity: MediaIdentity?): Long? {
            if (identity == null) return id
            val ids = byNameAndSize[identity.displayName to identity.sizeBytes].orEmpty()
                .filter { IdRemap.sameFile(it.second, identity) }
                .mapTo(sortedSetOf()) { it.first }
            return if (id in ids) id else ids.singleOrNull()
        }
    }

    /**
     * Edit recipes of the photos this run wrote. A recipe is stored against the file's revision, and
     * a restored file has a new one, so it is keyed to what the phone says the file's revision is
     * now. A recipe is also dropped when it describes another revision of the file than the backed-up one.
     */
    private fun remappedRecipes(prep: RestorePreparation, written: List<Written>): List<RestoredRecipe> {
        val revisionByOld = HashMap<Long, Pair<Long, Long>>() // old media id -> (new id, revision)
        val entryByOld = prep.set.entries.associateBy { it.mediaId }
        for (w in written) revisionByOld.putIfAbsent(w.entry.mediaId, w.committed.mediaId to w.committed.dateModifiedSeconds)
        for (item in prep.plan.items) {
            val here = item.matchedMediaId ?: continue
            val revision = prep.localRevisions[here] ?: continue
            revisionByOld.putIfAbsent(item.entry.mediaId, here to revision)
        }
        val photos = BackupPhotos(prep.set.entries)
        return BackupSections.recipes(prep.set.section(BackupSections.RECIPES)).mapNotNull { recipe ->
            val backupId = photos.idOf(recipe.mediaId, recipe.identity) ?: return@mapNotNull null
            val entry = entryByOld[backupId] ?: return@mapNotNull null
            if (entry.dateModifiedSeconds != recipe.dateModifiedSeconds) return@mapNotNull null
            val (newId, revision) = revisionByOld[backupId] ?: return@mapNotNull null
            if (revision <= 0) null else RestoredRecipe(newId, revision, recipe.recipe)
        }
    }

    /** What the person is told when the run stopped early. Cancellation passes through untouched. */
    private fun translate(problem: Throwable, prep: RestorePreparation, writtenCount: Int): Throwable {
        if (problem is CancellationException || problem is BackupRefused) return problem
        val saved = if (writtenCount == 0) "" else " ${BackupCopy.count(writtenCount, "file")} came back before it stopped."
        val again = " Run the restore again with the same folder and passphrase: what is already on the phone is skipped."
        return when {
            problem is StorageFull -> BackupRefused("The phone's storage is full.$saved Free some space.$again")
            problem is IOException -> BackupRefused("The backup folder could not be read.$saved Check that the card or drive is still connected.$again")
            else -> problem
        }
    }
}
