package com.fotoxplorr.app.sources.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.app.sources.gallery.SourceAssetEntry
import com.fotoxplorr.app.sources.gallery.SourceAssetsProvider
import com.fotoxplorr.app.sources.saf.GrantOutcome
import com.fotoxplorr.app.sources.saf.SafDocumentTree
import com.fotoxplorr.app.sources.saf.SafGrantRegistry
import com.fotoxplorr.app.sources.saf.SourceEnricher
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.db.sources.SourceAssetRecord
import com.fotoxplorr.core.db.sources.SourceRecord
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.index.sources.SourceAssetRow
import com.fotoxplorr.core.index.sources.SourceInfo
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

/** One source as the Sources screen shows it. Counts are of the files the last completed walk still saw. */
data class SourceStatus(
    val info: SourceInfo,
    val meta: SourceMeta,
    val files: Int,
    val bytes: Long,
    /** Files whose size, orientation and date are known. */
    val sized: Int,
    /** Files the sizing pass could not read (kept, retried later). */
    val unreadable: Int,
    val progress: IndexProgress?,
)

sealed interface AddResult {
    data class Added(val sourceRow: Long) : AddResult
    data class Duplicate(val existingName: String) : AddResult
    data class InsideExisting(val existingName: String) : AddResult
    data class ContainsExisting(val existingName: String) : AddResult
    data class GrantRefused(val reason: String) : AddResult
    data object Failed : AddResult
}

/**
 * The one door to `fotoz-sources.db` (ADR-020), and the owner of what the person did with their
 * folders and drives: add, remove, include, index.
 *
 * LAZY by construction. Nothing here opens the database until a source exists: [dbExists] says
 * whether the file is already there (a phone that never added a folder has no file), the provider
 * emits an empty list without touching it, and only [addSource] creates it. Wiring this into the
 * activity therefore costs a phone with no sources nothing.
 *
 * Removing a source forgets OUR rows and our own record of the folder; it never lists, reads,
 * writes or deletes a file in it, and it gives the Android grant back only when this source is what
 * made Android keep it and no other feature uses the tree. A source whose grant disappears becomes
 * REVOKED, keeps every row, and is hidden from the gallery until the person picks the folder again.
 *
 * @param sampleMs at most one re-read of the asset rows per this many milliseconds (an index writes
 *   a slice every moment; the gallery does not need to redraw per slice). 0 = no limit.
 */
class SourcesRuntime(
    private val dbExists: () -> Boolean,
    dbFactory: () -> FotozSourcesDatabase,
    private val grants: SourceGrants,
    private val meta: SourceMetaStore,
    private val indexerFor: (FotozSourcesDatabase) -> SourceIndexer,
    private val nameFor: (String) -> String = TreeRelations::suggestedName,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sampleMs: Long = 400L,
) {
    private val dbHolder = lazy(dbFactory)
    private val opened = MutableStateFlow(dbExists())
    private val database: FotozSourcesDatabase get() = dbHolder.value.also { opened.value = true }

    private val progressState = MutableStateFlow<Map<Long, IndexProgress>>(emptyMap())
    private val running = HashMap<Long, Job?>()

    /** True once the database has been opened by this process; false for a phone with no sources. */
    val databaseOpened: Boolean get() = dbHolder.isInitialized()

    fun progress(): StateFlow<Map<Long, IndexProgress>> = progressState.asStateFlow()

    private val presentRows: SharedFlow<List<SourceAssetRecord>> by lazy {
        database.sourceAssetsDao().observePresent()
            .conflate()
            .transform { emit(it); if (sampleMs > 0) delay(sampleMs) }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    /** What the gallery merges in (U3's seam). Emits an empty list, without opening anything, until a source exists. */
    val provider: SourceAssetsProvider = object : SourceAssetsProvider {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun assets(): Flow<List<SourceAssetEntry>> =
            opened.flatMapLatest { if (it) entries() else flowOf(NO_ENTRIES) }
    }

    private fun entries(): Flow<List<SourceAssetEntry>> =
        combine(database.sourcesDao().observeAll(), presentRows, meta.all()) { sources, rows, metas ->
            val shown = sources.mapNotNull { it.toInfo() }.filter { metas[it.row]?.included != false }.associateBy { it.row }
            rows.mapNotNull { r -> shown[r.sourceRow]?.let { SourceAssetEntry(r.toRow(), it) } }
        }.flowOn(Dispatchers.Default)

    /** The Sources screen's list. Empty, without opening anything, until a source exists. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun statuses(): Flow<List<SourceStatus>> = opened.flatMapLatest { if (!it) flowOf(emptyList()) else statusFlow() }

    private fun statusFlow(): Flow<List<SourceStatus>> =
        combine(database.sourcesDao().observeAll(), presentRows, meta.all(), progressState) { sources, rows, metas, progress ->
            val byRow = rows.groupBy { it.sourceRow }
            sources.mapNotNull { rec ->
                val info = rec.toInfo() ?: return@mapNotNull null
                val mine = byRow[rec.sourceRow].orEmpty()
                SourceStatus(
                    info = info,
                    meta = metas[rec.sourceRow] ?: SourceMeta(),
                    files = mine.size,
                    bytes = mine.sumOf { it.sizeBytes },
                    sized = mine.count { it.enrichState == 1 },
                    unreadable = mine.count { it.enrichState == 2 },
                    progress = progress[rec.sourceRow],
                )
            }
        }.flowOn(Dispatchers.Default)

    /** Adds the folder the picker returned. One source per tree; a tree inside or around another is refused. */
    suspend fun addSource(treeUri: String, displayName: String? = null): AddResult {
        val db = database
        val existing = db.sourcesDao().getAll().filter {
            val kind = SourceKind.fromWire(it.kind)
            kind == SourceKind.SAF_TREE || kind == SourceKind.USB_SAF
        }
        for (e in existing) {
            when (TreeRelations.relate(treeUri, e.rootLocator)) {
                TreeRelation.SAME -> return AddResult.Duplicate(e.displayName)
                TreeRelation.INSIDE_EXISTING -> return AddResult.InsideExisting(e.displayName)
                TreeRelation.CONTAINS_EXISTING -> return AddResult.ContainsExisting(e.displayName)
                TreeRelation.UNRELATED -> Unit
            }
        }
        val wasHeld = grants.isHeld(treeUri)
        if (!wasHeld) {
            val taken = grants.take(treeUri)
            if (taken is GrantOutcome.Refused) return AddResult.GrantRefused(taken.reason)
        }
        val row = try {
            db.sourcesDao().insert(
                SourceRecord(
                    kind = SourceKind.SAF_TREE.wire,
                    rootLocator = treeUri,
                    volumeUuid = TreeRelations.volumeOf(treeUri),
                    displayName = displayName?.takeIf { it.isNotBlank() } ?: nameFor(treeUri),
                    state = SourceState.ONLINE.wire,
                    createdMs = clock(),
                ),
            )
        } catch (e: Exception) {
            if (!wasHeld) grants.forget(treeUri)
            return AddResult.Failed
        }
        meta.update(row) { SourceMeta(tookGrant = !wasHeld) }
        return AddResult.Added(row)
    }

    /**
     * Forgets a source: its rows, its walk checkpoints and our record of it. Never reads or touches
     * a file in the folder. The Android grant goes back only if this source took it and nothing else uses it.
     */
    suspend fun removeSource(sourceRow: Long) {
        if (!opened.value) return
        val db = database
        val src = db.sourcesDao().get(sourceRow) ?: return
        synchronized(running) { running[sourceRow] }?.cancel()
        db.sourcesDao().remove(sourceRow)
        val mine = meta.get(sourceRow)
        meta.remove(sourceRow)
        progressState.update { it - sourceRow }
        val stillUsed = db.sourcesDao().getAll().any { it.rootLocator == src.rootLocator } ||
            grants.usedByOtherFeatures(src.rootLocator)
        if (mine.tookGrant && !stillUsed) grants.forget(src.rootLocator)
    }

    fun setIncluded(sourceRow: Long, included: Boolean) = meta.update(sourceRow) { it.copy(included = included) }

    fun setIndexOnConnect(sourceRow: Long, on: Boolean) = meta.update(sourceRow) { it.copy(indexOnConnect = on) }

    suspend fun sourceName(sourceRow: Long): String? = if (opened.value) database.sourcesDao().get(sourceRow)?.displayName else null

    /** Stops the index of one source. Its checkpoint stays, so the next index continues from it. */
    fun stopIndex(sourceRow: Long) {
        synchronized(running) { running[sourceRow] }?.cancel()
    }

    fun isIndexing(sourceRow: Long): Boolean = synchronized(running) { sourceRow in running }

    /**
     * Brings every source's state in line with the grants Android still holds: a lost grant is
     * REVOKED (rows kept), a grant that is held again lifts REVOKED. Opens nothing for a phone with no sources.
     */
    suspend fun refreshStates() {
        if (!opened.value) return
        val dao = database.sourcesDao()
        for (rec in dao.getAll()) {
            val state = SourceState.fromWire(rec.state) ?: continue
            val held = grants.isHeld(rec.rootLocator)
            when {
                !held && state != SourceState.REVOKED -> dao.setState(rec.sourceRow, SourceState.REVOKED.wire)
                held && state == SourceState.REVOKED -> dao.setState(rec.sourceRow, SourceState.ONLINE.wire)
            }
        }
    }

    /** Sources the person asked to be read again whenever they are there. */
    suspend fun indexOnConnectCandidates(): List<Long> {
        if (!opened.value) return emptyList()
        return database.sourcesDao().getAll().filter {
            meta.get(it.sourceRow).indexOnConnect && grants.isHeld(it.rootLocator)
        }.map { it.sourceRow }
    }

    /** Reads one source (walk, then sizing). Safe to call twice: the second call returns [IndexOutcome.Busy]. */
    suspend fun index(sourceRow: Long, onProgress: (IndexProgress) -> Unit = {}): IndexOutcome {
        if (!opened.value) return IndexOutcome.Missing
        val db = database
        val job = currentCoroutineContext()[Job]
        synchronized(running) {
            if (sourceRow in running) return IndexOutcome.Busy
            running[sourceRow] = job
        }
        progressState.update { it + (sourceRow to IndexProgress(IndexPhase.WALKING, 0L, null)) }
        try {
            return indexerFor(db).index(sourceRow) { p ->
                progressState.update { it + (sourceRow to p) }
                onProgress(p)
            }
        } finally {
            synchronized(running) { running.remove(sourceRow) }
            progressState.update { it - sourceRow }
        }
    }

    private fun SourceRecord.toInfo(): SourceInfo? {
        val kind = SourceKind.fromWire(kind) ?: return null
        return SourceInfo(sourceRow, kind, rootLocator, volumeUuid, displayName, SourceState.fromWire(state) ?: SourceState.ERROR)
    }

    private fun SourceAssetRecord.toRow() = SourceAssetRow(
        row = assetRow, sourceRow = sourceRow, documentId = documentId, displayName = displayName, mime = mime,
        sizeBytes = sizeBytes, width = width, height = height, durationMs = durationMs, dateTakenMs = dateTakenMs,
        dateModifiedMs = dateModifiedMs, pathInsideTree = path, contentUri = contentUri, present = present,
    )

    companion object {
        private val NO_ENTRIES: List<SourceAssetEntry> = emptyList()
        private const val DATABASE_NAME = "fotoz-sources.db"

        @Volatile private var instance: SourcesRuntime? = null

        /** The process's one runtime. Building it opens nothing; see the class note. */
        fun get(context: Context): SourcesRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** Test seam: the next [get] builds a fresh runtime (a Robolectric test brings its own application). */
        internal fun forgetInstance() { synchronized(this) { instance = null } }

        private fun create(app: Context): SourcesRuntime {
            val grants = SafSourceGrants(SafGrantRegistry(app.contentResolver))
            val enricher = SourceEnricher(app)
            val meta = PrefsSourceMetaStore(app)
            return SourcesRuntime(
                dbExists = { app.getDatabasePath(DATABASE_NAME).exists() },
                dbFactory = {
                    Room.databaseBuilder<FotozSourcesDatabase>(app, DATABASE_NAME)
                        .setDriver(BundledSQLiteDriver())
                        .setQueryCoroutineContext(Dispatchers.IO)
                        .build()
                },
                grants = grants,
                meta = meta,
                indexerFor = { db ->
                    SourceIndexer(
                        db = db,
                        grants = grants,
                        treeFor = { s -> SafDocumentTree(app, Uri.parse(s.rootLocator), grantHeld = { grants.isHeld(s.rootLocator) }) },
                        documentUri = { s, id -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(s.rootLocator), id).toString() },
                        facts = FactsReader { _, a ->
                            enricher.enrich(Uri.parse(a.contentUri), MediaFormat.classify(a.mime, a.displayName), a.sizeBytes)
                        },
                        meta = meta,
                    )
                },
            )
        }
    }
}
