package com.fotoxplorr.app.sources.runtime

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.fotoxplorr.app.sources.saf.SafAccess
import com.fotoxplorr.app.sources.saf.SafCopy
import com.fotoxplorr.app.sources.saf.SafHealth
import com.fotoxplorr.app.sources.saf.SourceFacts
import com.fotoxplorr.app.sources.saf.EnrichmentOutcome
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.db.sources.IndexRunRecord
import com.fotoxplorr.core.db.sources.SourceAssetRecord
import com.fotoxplorr.core.db.sources.SourceFolderRecord
import com.fotoxplorr.core.db.sources.SourceRecord
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.index.sources.IndexRunStatus
import com.fotoxplorr.core.index.sources.SourceState
import com.fotoxplorr.core.index.sources.SourceStateMachine
import com.fotoxplorr.core.index.sources.WalkOutcome
import com.fotoxplorr.core.index.walker.DocumentTree
import com.fotoxplorr.core.index.walker.TreeAccessException
import com.fotoxplorr.core.index.walker.TreeWalker
import com.fotoxplorr.core.index.walker.WalkBudget
import com.fotoxplorr.core.index.walker.WalkConfig
import com.fotoxplorr.core.index.walker.WalkCursor
import com.fotoxplorr.core.index.walker.WalkSlice
import com.fotoxplorr.core.index.walker.WalkStatus
import com.fotoxplorr.core.index.walker.WalkedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class IndexPhase { WALKING, SIZING }

/** How far an index run is. [fraction] is null while it cannot say. */
data class IndexProgress(val phase: IndexPhase, val filesFound: Long, val fraction: Float? = null)

/** Reads the facts a listing cannot give for one asset row. Production: [com.fotoxplorr.app.sources.saf.SourceEnricher]. */
fun interface FactsReader {
    suspend fun read(source: SourceRecord, asset: SourceAssetRecord): SourceFacts
}

/** The limits of the second (sizing) pass. */
data class EnrichConfig(
    /** How many files are read at once. */
    val concurrency: Int = 4,
    val chunk: Int = 64,
    /** The pass stops after reading this many bytes in total; the rest waits for the next index. */
    val maxBytes: Long = 256L * 1024 * 1024,
    /** An unreadable file is tried again by later passes until it has failed this many times. */
    val maxAttempts: Int = 3,
    /** Providers whose files are read without asking. A cloud app's files are never opened for sizing: reading one can download it. */
    val localAuthorities: Set<String> = setOf("com.android.externalstorage.documents"),
)

sealed interface IndexOutcome {
    /** The walk reached the end. [swept] files were marked no longer present (0 unless the walk was exhaustive). */
    data class Completed(
        val files: Long,
        val swept: Int,
        val exhaustive: Boolean,
        val sized: Int,
        val unreadable: Int,
        val deferred: Int,
    ) : IndexOutcome

    /** Stopped on request. The checkpoint stays, so the next index continues from it. */
    data class Cancelled(val files: Long) : IndexOutcome

    /** The walk could not finish. Nothing was marked missing and no row was removed. */
    data class Failed(val health: SafHealth, val message: String) : IndexOutcome

    /** The source is revoked or not reachable, so nothing was read at all. */
    data class Refused(val health: SafHealth) : IndexOutcome

    data object Missing : IndexOutcome

    /** That source is already being indexed. */
    data object Busy : IndexOutcome
}

/**
 * WP3.2 + WP3.4 prerequisite: walks one source into `fotoz-sources.db`, then sizes what it found.
 *
 * Rules it keeps (PHASE-3-DESIGN 1.x, ADR-020, TRAPS #8):
 *  - Each walker slice is persisted with its cursor in ONE transaction, so a kill at any point
 *    resumes with no gap (a directory repeated after a kill is absorbed: assets are upserted by
 *    `(source, document id)` with insert-ignore plus refresh, never REPLACE, so a row number and
 *    therefore a media id never changes).
 *  - A run that was left RUNNING (process death, cancel, unplugged drive, lost grant) is resumed
 *    from its checkpoint when it is recent; a stale or unreadable one is closed and a fresh walk starts.
 *  - "Not seen" becomes "not present" only through `sweepMissing`, only when the slice says
 *    `mayConcludeMissing` (complete and exhaustive), only while the grant is still held and the source
 *    is ONLINE. A failed, cancelled, partial or revoked walk never sweeps. Nothing is ever deleted.
 *  - Nothing is read while the grant is gone or the drive is not there.
 *  - The sizing pass reads a bounded head of each file, a few at a time, off the main thread; a file it
 *    cannot read is counted as unreadable (and retried later), never dropped.
 *
 * Not run against a real provider on a device.
 *
 * @param sliceHook test seam, called after every committed slice; a test throws from it to stand in for process death.
 */
class SourceIndexer(
    private val db: FotozSourcesDatabase,
    private val grants: SourceGrants,
    private val treeFor: (SourceRecord) -> DocumentTree,
    private val documentUri: (SourceRecord, String) -> String,
    private val facts: FactsReader,
    private val meta: SourceMetaStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val walkConfig: WalkConfig = WalkConfig(),
    private val sliceBudget: WalkBudget = WalkBudget(maxDirectories = 64, maxFiles = 2_000),
    private val enrichConfig: EnrichConfig = EnrichConfig(),
    private val sliceHook: (Int) -> Unit = {},
) {
    suspend fun index(sourceRow: Long, onProgress: (IndexProgress) -> Unit = {}): IndexOutcome {
        val src = db.sourcesDao().get(sourceRow) ?: return IndexOutcome.Missing
        if (!grants.isHeld(src.rootLocator)) {
            setState(src, SourceState.REVOKED)
            return IndexOutcome.Refused(SafHealth.REVOKED)
        }
        var state = SourceState.fromWire(src.state) ?: SourceState.ERROR
        val tree = treeFor(src)
        // A grant that is held again lifts REVOKED (GrantRestored); an OFFLINE source must answer one listing first.
        if (state == SourceState.REVOKED) {
            state = SourceState.ONLINE
            setState(src, state)
        }
        if (state == SourceState.OFFLINE) {
            val probe = withContext(Dispatchers.IO) { runCatching { tree.children(tree.rootDocumentId) } }
            val failure = probe.exceptionOrNull()
            if (failure != null) {
                val health = SafAccess.healthAfter((failure as? TreeAccessException)?.failure ?: com.fotoxplorr.core.index.walker.TreeFailure.ERROR, grants.isHeld(src.rootLocator))
                setState(src, health.toState())
                return IndexOutcome.Refused(health)
            }
            state = SourceState.ONLINE
            setState(src, state)
        }

        val walkOutcome = walk(src, tree, onProgress)
        if (walkOutcome !is WalkEnd.Done) return walkOutcome.asOutcome()
        val done = walkOutcome
        val sizing = enrich(src, done.files, onProgress)
            ?: return IndexOutcome.Cancelled(done.files)
        return IndexOutcome.Completed(done.files, done.swept, done.exhaustive, sizing.sized, sizing.unreadable, sizing.deferred)
    }

    private sealed interface WalkEnd {
        data class Done(val files: Long, val swept: Int, val exhaustive: Boolean) : WalkEnd
        data class Stopped(val files: Long) : WalkEnd
        data class Broke(val health: SafHealth, val message: String) : WalkEnd

        fun asOutcome(): IndexOutcome = when (this) {
            is Stopped -> IndexOutcome.Cancelled(files)
            is Broke -> IndexOutcome.Failed(health, message)
            is Done -> IndexOutcome.Completed(files, swept, exhaustive, 0, 0, 0)
        }
    }

    private suspend fun walk(src: SourceRecord, tree: DocumentTree, onProgress: (IndexProgress) -> Unit): WalkEnd {
        val runs = db.indexRunsDao()
        val now = clock()
        var resume: WalkCursor? = null
        var run = runs.latestRunning(src.sourceRow)
        if (run != null) {
            val fresh = now - run.startedMs in 0..RESUME_WINDOW_MS
            val cursor = run.cursor?.let { WalkCursor.decode(it) }?.takeIf { it.rootDocumentId == tree.rootDocumentId }
            if (fresh && (run.cursor == null || cursor != null)) {
                resume = cursor
            } else {
                runs.finish(run.runId, IndexRunStatus.CANCELLED.name, now, "The saved position could not be used.")
                run = null
            }
        }
        val runId = run?.runId ?: runs.start(
            IndexRunRecord(sourceRow = src.sourceRow, status = IndexRunStatus.RUNNING.name, full = true, cursor = null, startedMs = now),
        )

        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        val walker = TreeWalker(tree, walkConfig)
        var cursor = resume ?: walker.start()
        var slices = 0
        var peak = 0f
        val last: WalkSlice = withContext(Dispatchers.IO) {
            var finished: WalkSlice? = null
            while (finished == null) {
                val slice = walker.advance(cursor, sliceBudget, cancelled)
                // Files and cursor together, and even when this coroutine was just cancelled: they describe the same moment.
                withContext(NonCancellable) { commit(src, runId, slice) }
                slices++
                val c = slice.cursor.counters
                val pending = slice.cursor.pending.size
                val fraction = if (c.directoriesDone + pending == 0) null else c.directoriesDone.toFloat() / (c.directoriesDone + pending)
                peak = maxOf(peak, (fraction ?: 0f).coerceAtMost(0.99f))
                onProgress(IndexProgress(IndexPhase.WALKING, c.mediaFound.toLong(), peak))
                sliceHook(slices)
                cursor = slice.cursor
                if (slice.status != WalkStatus.Running) finished = slice
            }
            finished
        }
        val found = last.cursor.counters.mediaFound.toLong()

        return when (val status = last.status) {
            is WalkStatus.Cancelled -> WalkEnd.Stopped(found) // the run stays RUNNING: the next index resumes it
            is WalkStatus.Failed -> {
                val held = grants.isHeld(src.rootLocator)
                val health = SafAccess.healthAfter(status.failure, held)
                // A drive that was pulled or a grant that was lost leaves the checkpoint to resume from; a provider error starts afresh next time.
                if (health == SafHealth.ERROR) withContext(NonCancellable) { runs.finish(runId, IndexRunStatus.FAILED.name, clock(), status.message) }
                setState(src, health.toState())
                val message = health.message ?: status.message
                meta.update(src.sourceRow) { it.copy(lastError = message) }
                WalkEnd.Broke(health, message)
            }
            is WalkStatus.Complete -> withContext(NonCancellable) {
                val record = runs.get(runId)
                if (record != null) {
                    runs.update(
                        record.copy(
                            status = IndexRunStatus.COMPLETED.name,
                            full = status.exhaustive,
                            cursor = null,
                            filesSeen = found,
                            finishedMs = clock(),
                            error = null,
                        ),
                    )
                }
                // The grant answers the walker's last listing, not this moment: ask again before concluding anything is gone.
                if (!grants.isHeld(src.rootLocator)) {
                    setState(src, SourceState.REVOKED)
                    meta.update(src.sourceRow) { it.copy(lastError = SafCopy.REVOKED) }
                    return@withContext WalkEnd.Broke(SafHealth.REVOKED, SafCopy.REVOKED)
                }
                setState(src, SourceState.ONLINE)
                val outcome = if (last.mayConcludeMissing) WalkOutcome.COMPLETED_FULL else WalkOutcome.COMPLETED_PARTIAL
                val swept = if (last.mayConcludeMissing && SourceStateMachine.maySweep(SourceState.ONLINE, outcome)) {
                    db.sourceAssetsDao().sweepMissing(src.sourceRow, runId)
                } else 0
                meta.update(src.sourceRow) { it.copy(lastIndexedMs = clock(), lastError = null) }
                WalkEnd.Done(found, swept, status.exhaustive)
            }
            WalkStatus.Running -> error("a finished walk is not running")
        }
    }

    private suspend fun commit(src: SourceRecord, runId: Long, slice: WalkSlice) {
        val assets = db.sourceAssetsDao()
        val folders = db.sourceFoldersDao()
        val runs = db.indexRunsDao()
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val seen = HashSet<String>()
                for (f in slice.files) {
                    if (seen.add(f.parentDocumentId)) {
                        folders.insertIgnore(SourceFolderRecord(sourceRow = src.sourceRow, documentId = f.parentDocumentId, path = f.directoryPath, lastSeenRun = runId))
                        folders.touch(src.sourceRow, f.parentDocumentId, f.directoryPath, runId)
                    }
                    val uri = documentUri(src, f.documentId)
                    val record = f.toRecord(src.sourceRow, runId, uri)
                    if (assets.insertIgnore(record) == -1L) {
                        assets.refresh(
                            src.sourceRow, f.documentId, record.displayName, record.mime, record.sizeBytes,
                            record.dateModifiedMs, record.path, uri, runId,
                        )
                    }
                }
                runs.checkpoint(runId, slice.cursor.encode(), slice.cursor.counters.mediaFound.toLong())
            }
        }
    }

    private class Sizing(val sized: Int, val unreadable: Int, val deferred: Int)

    /** The sizing pass. Null when it was cancelled. */
    private suspend fun enrich(src: SourceRecord, found: Long, onProgress: (IndexProgress) -> Unit): Sizing? {
        val authority = src.rootLocator.removePrefix("content://").substringBefore('/')
        if (authority !in enrichConfig.localAuthorities) return Sizing(0, 0, 0)
        val dao = db.sourceAssetsDao()
        val todo = dao.forSource(src.sourceRow).filter {
            it.present && it.contentUri != null &&
                (it.enrichState == 0 || (it.enrichState == 2 && it.enrichAttempts < enrichConfig.maxAttempts))
        }
        if (todo.isEmpty()) return Sizing(0, 0, 0)
        val job = currentCoroutineContext()[Job]
        val gate = Semaphore(enrichConfig.concurrency.coerceAtLeast(1))
        var bytes = 0L
        var sized = 0
        var unreadable = 0
        var handled = 0
        for (batch in todo.chunked(enrichConfig.chunk.coerceAtLeast(1))) {
            if (job?.isActive == false) return null
            if (bytes >= enrichConfig.maxBytes) return Sizing(sized, unreadable, todo.size - handled)
            val results = coroutineScope {
                batch.map { asset -> async(Dispatchers.IO) { gate.withPermit { asset to readOne(src, asset) } } }.awaitAll()
            }
            withContext(NonCancellable) {
                db.useWriterConnection { t ->
                    t.immediateTransaction {
                        for ((asset, f) in results) {
                            bytes += f.bytesRead
                            val sizeKnown = (f.width ?: 0) > 0 && (f.height ?: 0) > 0
                            when {
                                f.outcome == EnrichmentOutcome.SKIPPED -> Unit
                                f.outcome == EnrichmentOutcome.READ && sizeKnown ||
                                    f.outcome == EnrichmentOutcome.PARTIAL && sizeKnown -> {
                                    dao.setEnriched(asset.assetRow, f.width!!, f.height!!, f.durationMillis ?: 0L, f.dateTakenMillis ?: 0L)
                                    sized++
                                }
                                else -> {
                                    dao.setUnreadable(asset.assetRow)
                                    unreadable++
                                }
                            }
                        }
                    }
                }
            }
            handled += batch.size
            onProgress(IndexProgress(IndexPhase.SIZING, found, handled.toFloat() / todo.size))
        }
        return Sizing(sized, unreadable, 0)
    }

    private suspend fun readOne(src: SourceRecord, asset: SourceAssetRecord): SourceFacts =
        try {
            facts.read(src, asset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceFacts(EnrichmentOutcome.UNREADABLE)
        }

    private suspend fun setState(src: SourceRecord, state: SourceState) {
        db.sourcesDao().setState(src.sourceRow, state.wire)
    }

    companion object {
        /** A left-over RUNNING walk older than this is closed instead of resumed: what it saw may be long out of date. */
        const val RESUME_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

        fun SafHealth.toState(): SourceState = when (this) {
            SafHealth.ONLINE -> SourceState.ONLINE
            SafHealth.OFFLINE -> SourceState.OFFLINE
            SafHealth.REVOKED -> SourceState.REVOKED
            SafHealth.ERROR -> SourceState.ERROR
        }

        internal fun WalkedFile.toRecord(sourceRow: Long, run: Long, uri: String) = SourceAssetRecord(
            sourceRow = sourceRow,
            documentId = documentId,
            displayName = name,
            mime = mimeFor(this),
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            dateModifiedMs = lastModifiedMillis,
            path = directoryPath,
            contentUri = uri,
            lastSeenRun = run,
        )

        /** The provider's media type when it names an image or video, else one from what the bytes or name said. */
        internal fun mimeFor(f: WalkedFile): String {
            val given = f.mimeType.trim().lowercase()
            if (given.startsWith("image/") || given.startsWith("video/")) return given
            return when (f.format) {
                MediaFormat.Video -> "video/mp4"
                MediaFormat.Jpeg -> "image/jpeg"
                MediaFormat.Png -> "image/png"
                MediaFormat.Gif -> "image/gif"
                MediaFormat.WebP -> "image/webp"
                MediaFormat.Heif -> "image/heic"
                MediaFormat.Bmp -> "image/bmp"
                MediaFormat.Tiff -> "image/tiff"
                MediaFormat.Avif -> "image/avif"
                else -> "image/*"
            }
        }
    }
}
