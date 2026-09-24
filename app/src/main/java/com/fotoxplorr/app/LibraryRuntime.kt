package com.fotoxplorr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.app.audio.AndroidAudioMediaStoreScanner
import com.fotoxplorr.app.audio.AudioIndexer
import com.fotoxplorr.app.audio.InMemoryAudioRepository
import com.fotoxplorr.app.audio.PrefsAudioScanWatermark
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.formats.AnimationIndex
import com.fotoxplorr.app.media.AndroidMediaStoreScanner
import com.fotoxplorr.app.media.MediaIndexer
import com.fotoxplorr.app.media.MediaStoreChangeObserver
import com.fotoxplorr.app.media.PrefsScanWatermark
import com.fotoxplorr.app.media.ScanEvent
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.migration.AndroidLegacySource
import com.fotoxplorr.app.migration.AndroidMigrationEnvironment
import com.fotoxplorr.app.migration.AndroidProjectionParityCheck
import com.fotoxplorr.app.migration.MigrationStateStore
import com.fotoxplorr.app.organize.LegacyCatalogMigration
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.FotozVectorsDatabase
import com.fotoxplorr.core.db.migration.MigrationToV2
import com.fotoxplorr.core.organize.ScanPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * What a scan is doing right now, for the UI's own banner. Was declared inside
 * [FotoXplorrActivity] until P0-09 moved scanning itself out of the Activity; living here
 * instead of `media/` because it is UI-facing vocabulary ("Indexing", "Error"), not scan
 * mechanics — [ScanEvent]/[ScanPlan] stay the Android-free types the indexer itself speaks.
 */
sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Int, val discovered: Int) : ScanState

    /** @param incremental true when this was a delta pass (a few changed items), not a full library scan. */
    data class Complete(val total: Int, val incremental: Boolean = false) : ScanState
    data class Error(val message: String) : ScanState
}

/**
 * The process's one media-scanning engine (P0-09).
 *
 * Before this, [FotoXplorrActivity] built its own [SqliteMediaRepository]/[MediaIndexer] with
 * `remember { … }` and ran the scan-request consumer loop in a `LaunchedEffect` — both torn down
 * and rebuilt on every Activity recreation, including a plain rotation before P0-05 added the
 * interim `configChanges` and still on process death today. Every recreation re-sent a FULL scan
 * request, so "Indexing 3456 of 21526" could drop back to 0 on a rotation the config-changes line
 * doesn't cover, or the process being killed and restored. [LibraryBackgroundWork] independently
 * built a second [SqliteMediaRepository] of its own for the same reason — two in-memory mirrors of
 * one database, the same failure family [com.fotoxplorr.app.organize.LibraryStore]'s own KDoc
 * warns about.
 *
 * [get] is the one way to reach this — the same double-checked-locking singleton shape
 * [com.fotoxplorr.app.organize.LibraryStore.get] already uses in this codebase, for a genuinely
 * process-wide instance rather than one scoped to whichever Activity happened to construct it.
 * [FotoXplorrApplication.onCreate] calls [get] once to force construction at process start; every
 * later caller (the Activity, [LibraryBackgroundWork]) gets back the exact same instance.
 */
class LibraryRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: SqliteMediaRepository = SqliteMediaRepository(appContext)
    private val indexer = MediaIndexer(
        scanner = AndroidMediaStoreScanner(appContext.contentResolver),
        repository = repository,
        watermark = PrefsScanWatermark(appContext),
        onSweepRefused = { catalogueSize, missingCount ->
            Log.i(
                TAG,
                "sweep refused: catalogueSize=$catalogueSize missingCount=$missingCount " +
                    "(rows left in place)",
            )
        },
    )

    // P0-14: which media ids actually animate, kept fresh from here rather than a user-triggered
    // action (unlike RecognitionIndexer) -- it is cheap enough (a bounded byte read per candidate
    // file, no ML model) to just run after every scan the same way the scan itself already does,
    // so the Animated album and the viewer are never stale just because nobody tapped "index".
    val animationIndex: AnimationIndex = AnimationIndex(appContext)

    // WP1.3 (ADR-011): fotoz.db / fotoz-vectors.db, real device-persisted databases (not
    // in-memory -- compare :core:db's own smoke/migration tests, which use
    // Room.inMemoryDatabaseBuilder deliberately). Built unconditionally, whether or not
    // migration has run yet: MigrationToV2.run() itself is the thing that decides whether
    // there's anything to do (see the init block below).
    val fotozDb: FotozDatabase = Room.databaseBuilder<FotozDatabase>(appContext, "fotoz.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val fotozVectorsDb: FotozVectorsDatabase = Room.databaseBuilder<FotozVectorsDatabase>(appContext, "fotoz-vectors.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val migrationState = MigrationStateStore(appContext)

    // Audio keeps its own pipeline, parallel to the photo/video one above rather than folded into
    // it -- see AudioAsset's own doc for why the two asset types stay apart everywhere. It still
    // needs the SAME once-per-process guard the photo/video scan does, which is why it lives here
    // too rather than back in the Activity.
    val audioRepository: InMemoryAudioRepository = InMemoryAudioRepository()
    private val audioIndexer = AudioIndexer(
        scanner = AndroidAudioMediaStoreScanner(appContext.contentResolver),
        repository = audioRepository,
        watermark = PrefsAudioScanWatermark(appContext),
    )

    // Rescans are REQUESTS on a conflated channel, not a recomposition key -- see ScanPlan's own
    // doc for the "cancelled and restarted under churn" failure this avoids. Audio keeps a
    // separate channel: a Kotlin Channel hands each element to exactly one collector, so one
    // shared channel with two independent consumer loops would only let each loop see some of
    // the requests, not all of them.
    private val scanRequests = Channel<Boolean>(Channel.CONFLATED)
    private val audioScanRequests = Channel<Boolean>(Channel.CONFLATED)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private var initialScanRequested = false
    private var initialAudioScanRequested = false
    private var changeObserverRegistered = false

    init {
        // P0-17: one-shot, guarded by its own flag -- see LegacyCatalogMigration's own KDoc for
        // why this runs here rather than lazily from wherever a caller first touches favourites,
        // tags or collections.
        LegacyCatalogMigration(appContext, LibraryStore.get(appContext), FavoriteStore(appContext)).run()

        // WP1.3 (ADR-011): the catalogue-v2 migration. Runs after LegacyCatalogMigration above --
        // that one first brings the CURRENT old stores up to date from an even older (V1) format,
        // and MigrationToV2 reads FROM those current old stores.
        //
        // Deliberately calling only run(), not runCleanupIfDue(): run() driving all the way
        // through CUTOVER is safe to fire unconditionally on every process start -- nothing in
        // this app yet reads MigrationStateStore.isCutoverComplete() to change its own behaviour
        // (no store is rewired onto fotoz.db yet, see MASTER-PROGRESS.md's WP1.3 Decisions), so a
        // real cutover firing has no user-visible effect today; it only proves the whole pipeline
        // end to end against this device's real data. runCleanupIfDue() is different: it can
        // really delete the six old SQLite files and four prefs files two starts after cutover,
        // which would be destructive with nothing yet reading their fotoz.db replacement -- wiring
        // that call is the store-rewiring work's own job, not this one's.
        scope.launch {
            val migration = MigrationToV2(
                db = fotozDb,
                vectorsDb = fotozVectorsDb,
                legacy = AndroidLegacySource(appContext),
                environment = AndroidMigrationEnvironment(appContext),
                projectionParity = AndroidProjectionParityCheck(
                    context = appContext,
                    repository = repository,
                    animationIndex = animationIndex,
                    assetDao = fotozDb.assetDao(),
                    assetUserDao = fotozDb.assetUserDao(),
                    keywordDao = fotozDb.keywordDao(),
                    folderLockDao = fotozDb.folderLockDao(),
                    recognitionDao = fotozDb.recognitionDao(),
                    traitDao = fotozDb.traitDao(),
                ),
            )
            try {
                when (val result = migration.run()) {
                    is MigrationToV2.Result.Completed -> Log.i(TAG, "catalogue v2 migration completed")
                    is MigrationToV2.Result.AlreadyCutOver -> {} // the common case on every later start.
                    is MigrationToV2.Result.VerifyFailed -> Log.w(TAG, "catalogue v2 migration VERIFY failed: ${result.detail}")
                }
            } catch (t: Throwable) {
                // Best-effort background work: a bug here (unexpected old-store data, a device
                // out of disk space mid-copy, ...) must not crash the app that's still running
                // entirely on the old stores regardless. MigrationToV2 already recorded the
                // failing step's own detail in migration_progress before rethrowing; the next
                // process start's run() resumes from there.
                Log.e(TAG, "catalogue v2 migration failed, will retry on next start", t)
            }
        }
        scope.launch {
            for (userRequested in scanRequests) {
                indexer.refresh(userRequested = userRequested, partialAccess = hasPartialMediaAccess(appContext))
                    .collect { event ->
                        _scanState.value = when (event) {
                            is ScanEvent.Started -> _scanState.value.takeIf { it is ScanState.Scanning }
                                ?: ScanState.Scanning(0, 0)
                            is ScanEvent.Progress -> ScanState.Scanning(event.scanned, event.discovered)
                            is ScanEvent.AssetFound -> _scanState.value
                            is ScanEvent.Completed -> ScanState.Complete(
                                total = event.total,
                                incremental = event.plan is ScanPlan.Delta,
                            )
                            is ScanEvent.Failed ->
                                ScanState.Error(event.error.message ?: "Unable to scan media")
                        }
                        // Fire-and-forget on this same scope, deliberately NOT awaited here: the
                        // scan-request loop must keep consuming the next request while a big
                        // library's own sniff pass is still working through it. A sniff triggered
                        // by two scans completing in quick succession can overlap harmlessly --
                        // sniffPending's own staleness check makes a duplicate pass a no-op once
                        // the first one's upserts have landed.
                        if (event is ScanEvent.Completed) {
                            val current = repository.awaitLoaded()
                            scope.launch {
                                animationIndex.sniffPending(current)
                                animationIndex.removeMissing(current.map { it.id }.toSet())
                            }
                        }
                    }
            }
        }
        scope.launch {
            for (userRequested in audioScanRequests) {
                audioIndexer.refresh(userRequested = userRequested).collect { }
            }
        }
    }

    /** Requests a full scan, but only the first time this is ever called for this process —
     * every later call is a no-op. Call this from wherever the UI already knows permission is
     * granted; it is safe to call again on every recreation. */
    @Synchronized
    fun ensureInitialScan() {
        if (initialScanRequested) return
        initialScanRequested = true
        scanRequests.trySend(true)
    }

    fun requestScan(userRequested: Boolean) {
        scanRequests.trySend(userRequested)
    }

    /** Audio's own [ensureInitialScan]. */
    @Synchronized
    fun ensureInitialAudioScan() {
        if (initialAudioScanRequested) return
        initialAudioScanRequested = true
        audioScanRequests.trySend(true)
    }

    fun requestAudioScan(userRequested: Boolean) {
        audioScanRequests.trySend(userRequested)
    }

    /** Subscribes to MediaStore changes exactly once per process, driving both the photo/video
     * and audio scan requests off the same observer -- registered once when permission is first
     * known to be granted, unlike the two independent per-composition registrations this replaced. */
    @Synchronized
    fun ensureChangeObserverRegistered() {
        if (changeObserverRegistered) return
        changeObserverRegistered = true
        val observer = MediaStoreChangeObserver(appContext.contentResolver)
        scope.launch {
            // One screenshot emits several MediaStore notifications (insert, thumbnail,
            // metadata). Debouncing collapses that burst into a single delta pass.
            observer.changes().debounce(MEDIA_CHANGE_DEBOUNCE_MS).collect {
                requestScan(false)
                // Audio permission is granted independently and later than photo/video access
                // (P0-10) -- querying MediaStore.Audio before it is granted would fail, so this
                // checks the SAME way ensureInitialAudioScan's own callers in the Activity do,
                // rather than assuming a change worth rescanning for also means audio is readable.
                if (hasAudioPermission(appContext)) requestAudioScan(false)
            }
        }
    }

    companion object {
        private const val TAG = "LibraryRuntime"

        /** See [ensureChangeObserverRegistered]'s own doc for why this debounce exists. */
        private const val MEDIA_CHANGE_DEBOUNCE_MS = 800L

        @Volatile
        private var instance: LibraryRuntime? = null

        /** The process's one [LibraryRuntime]. See the class KDoc for why there must be only one. */
        fun get(context: Context): LibraryRuntime =
            instance ?: synchronized(this) {
                instance ?: LibraryRuntime(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * True when this app currently holds only Android 14+'s limited "selected photos" grant
 * ([Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED]) rather than full [Manifest.permission
 * .READ_MEDIA_IMAGES]/[Manifest.permission.READ_MEDIA_VIDEO] access. A scan under that grant can
 * never enumerate the whole library, which [SweepPolicy] needs to know before it ever agrees to
 * remove a row a scan didn't see (TRAPS #8).
 *
 * A top-level, [Context]-based function rather than the [FotoXplorrActivity] extension it used to
 * be, so [LibraryRuntime] -- which is not an Activity -- can call it too.
 */
internal fun hasPartialMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) != PackageManager.PERMISSION_GRANTED

/**
 * Whether the app can currently query `MediaStore.Audio` (P0-10). `READ_MEDIA_AUDIO` (API 33+)
 * is requested separately from [FotoXplorrActivity]'s photo/video permissions, only when the user
 * opens the Audio library — granting it must never count as "media access granted" for the
 * photo/video gallery, so it is deliberately not part of `mediaReadPermissions()`. Below API 33,
 * `MediaStore.Audio` queries are covered by the same `READ_EXTERNAL_STORAGE` permission the
 * photo/video flow already requests pre-Tiramisu, so there is nothing audio-specific to check.
 *
 * A top-level, [Context]-based function for the same reason [hasPartialMediaAccess] is one:
 * [LibraryRuntime]'s own change-observer callback needs to check it too, and it is not an Activity.
 */
internal fun hasAudioPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
