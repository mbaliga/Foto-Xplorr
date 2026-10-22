package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a tap on Download or Resume is for, kept while the mobile-data question is open. */
internal sealed interface DownloadRequest {
    data class Fresh(val name: String) : DownloadRequest
    data class Existing(val region: StoredRegion) : DownloadRequest
}

/** The open "Download over mobile data?" question. */
internal data class MeteredQuestion(val asked: DownloadState.ConfirmMetered, val request: DownloadRequest) {
    val bytes: Long get() = asked.estimate.bytes
}

/** Everything the pane draws that is not typed text. */
internal data class PaneUiState(
    val loaded: Boolean = false,
    val listFailed: Boolean = false,
    val regions: List<StoredRegion> = emptyList(),
    /** Newest reading for each area being fetched right now. */
    val live: Map<Long, RegionProgress> = emptyMap(),
    /** Why each stopped area stopped; an area missing here was found after a relaunch. */
    val pauseReasons: Map<Long, PauseReason> = emptyMap(),
    /** Why a download gave up on each area this session (a size limit, the provider, the engine). */
    val failures: Map<Long, FailureReason> = emptyMap(),
    /** Map downloads the job runner has running, which can be ahead of the engine's own list. */
    val jobsRunning: Int = 0,
    val question: MeteredQuestion? = null,
    val deleting: StoredRegion? = null,
    val notice: String? = null,
) {
    val downloadRunning: Boolean get() = jobsRunning > 0 || regions.any { it.running }
}

/**
 * The Connect pane's behaviour, apart from drawing: what opening it does (list the stored areas,
 * nothing else), and what each tap does. A plain class over [OfflineRegionEngine] and [JobRunner] so
 * a JVM test drives it with the fake engine.
 *
 * Rules, the same ones the guard and the job already keep:
 * - Opening, refreshing and watching run [OfflineRegionEngine.list] and [OfflineRegionEngine.progress]
 *   only. A download job is launched in exactly one place ([launch]), reached only from a
 *   [PaneStep.Launch], which [PaneDownloadFlow] returns only for a tap on Download or Resume or an
 *   accepted mobile-data question (TRAPS #61, #71).
 * - Closing the pane stops this class's own listening and leaves every download job running; the job
 *   is the runner's, and a pane opened later picks it up from the engine's list.
 * - Pause is cancelling the job (which pauses, never deletes). Only [confirmDelete] deletes, only the
 *   area the person was shown in the confirmation (TRAPS #64).
 *
 * Not saved across process death: the running downloads, the open question and any notice. What a
 * download fetched, and the area itself, persist in the map library's database. After a relaunch
 * [refresh] lists them as stopped, not running; an unfinished one has no entry in
 * [PaneUiState.pauseReasons], which reads as [PauseReason.RESTARTED] ("Paused, tap resume to
 * continue"), and it restarts only from a tap on Resume.
 */
internal class OfflineMapsController(
    private val engine: OfflineRegionEngine,
    private val runner: JobRunner,
    private val network: ConnectionCheck,
    private val nowMillis: () -> Long,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(PaneUiState())
    val state: StateFlow<PaneUiState> = mutable.asStateFlow()

    private val collectors = HashMap<Long, Job>()
    private var watching: Job? = null

    /** The pane is on screen: read what is stored and follow the job list. Starts no download. */
    fun open() {
        scope.launch { refresh() }
        watching?.cancel()
        watching = scope.launch {
            var last: Int? = null
            runner.jobs.map { jobs -> jobs.count { it.kind == ActivityKind.DOWNLOADING_MAP } }
                .distinctUntilChanged()
                .collect { count ->
                    mutable.update { it.copy(jobsRunning = count) }
                    // A download ended: what it left is only in the engine's list.
                    val before = last
                    last = count
                    if (before != null && count < before) refresh()
                }
        }
    }

    /** The pane left the screen. Stops listening here; no download is touched. */
    fun close() {
        watching?.cancel()
        watching = null
        collectors.values.forEach { it.cancel() }
        collectors.clear()
    }

    /** What the buttons are decided against, right now. */
    fun env(): PaneEnv = envOf(mutable.value)

    private fun envOf(state: PaneUiState) = PaneEnv(
        throttleInstalled = engine.throttleInstalled,
        canDownloadBox = { box -> MapLibreRegionEngine.canDownload(box) },
        saved = state.regions.map { it.record },
        covering = OfflineMapsPaneModel.coveringRecords(state.regions),
        // The runner's own list is read here, not only the collected copy in [state]: a second tap
        // that lands before the collector has caught up must still see the first download.
        downloadRunning = state.downloadRunning ||
            runner.jobs.value.any { it.kind == ActivityKind.DOWNLOADING_MAP },
    )

    suspend fun refresh() {
        when (val listed = engine.list()) {
            is EngineResult.Failure -> mutable.update { it.copy(loaded = true, listFailed = true) }
            is EngineResult.Ok -> {
                val regions = listed.value
                syncCollectors(regions)
                mutable.update { old ->
                    val unfinished = regions.filter { !it.progress.complete }.mapTo(HashSet()) { it.id }
                    old.copy(
                        loaded = true,
                        listFailed = false,
                        regions = regions,
                        pauseReasons = old.pauseReasons.filterKeys { it in unfinished },
                        failures = old.failures.filterKeys { it in unfinished },
                        live = old.live.filterKeys { id -> regions.any { it.id == id && it.running } },
                    )
                }
            }
        }
    }

    /** One listener per area that is being fetched; an area that stopped loses its listener. */
    private fun syncCollectors(regions: List<StoredRegion>) {
        val running = regions.filter { it.running }.map { it.id }.toSet()
        collectors.keys.filter { it !in running }.forEach { collectors.remove(it)?.cancel() }
        for (id in running) {
            if (id in collectors) continue
            collectors[id] = scope.launch {
                engine.progress(id).collect { reading -> mutable.update { it.copy(live = it.live + (id to reading)) } }
            }
        }
    }

    // ---- taps ----

    /** A tap on Download for [option]. [typedName] is what the person typed, or null. */
    fun download(option: AreaOption, typedName: String?) {
        val current = mutable.value
        val button = OfflineMapsPaneModel.button(option, envOf(current))
        if (!button.enabled) {
            note(button.note)
            return
        }
        // Before anything is created: with no connection the area would sit in one of the six slots
        // and fetch nothing.
        val connection = network.connection()
        if (connection == Connection.NONE) {
            note(OfflineMapsCopy.NO_CONNECTION)
            return
        }
        val name = OfflineMapsPaneModel.uniqueName(
            OfflineMapsPaneModel.nameFor(option, typedName),
            current.regions.map { it.record },
        )
        val step = PaneDownloadFlow.tapDownload(
            option.box, option.verdict, engine.throttleInstalled, connection == Connection.METERED,
        )
        proceed(step, DownloadRequest.Fresh(name))
    }

    /** A tap on Resume for stored area [id]. */
    fun resume(id: Long) {
        val current = mutable.value
        val region = current.regions.firstOrNull { it.id == id } ?: return
        val row = OfflineMapsPaneModel.savedRows(
            listOf(region), current.pauseReasons, current.live, envOf(current), failures = current.failures,
        ).single()
        if (!row.canResume) {
            note(row.resumeNote ?: row.statusLine.takeIf { row.stoppedAtLimit })
            return
        }
        val connection = network.connection()
        if (connection == Connection.NONE) {
            note(OfflineMapsCopy.NO_CONNECTION)
            return
        }
        val reason = current.pauseReasons[id] ?: PauseReason.RESTARTED
        val step = PaneDownloadFlow.tapResume(
            region.record, region.progress, reason, engine.throttleInstalled, connection == Connection.METERED,
        )
        proceed(step, DownloadRequest.Existing(region))
    }

    /** The person accepted "Download over mobile data?". */
    fun confirmMetered() {
        val question = mutable.value.question ?: return
        mutable.update { it.copy(question = null) }
        proceed(PaneDownloadFlow.confirm(question.asked, engine.throttleInstalled), question.request)
    }

    /** The person declined it. Nothing starts. */
    fun declineMetered() {
        val question = mutable.value.question ?: return
        PaneDownloadFlow.decline(question.asked)
        mutable.update { it.copy(question = null) }
    }

    /** Pause area [id]: cancel its job, which pauses and keeps what was fetched. */
    fun pause(id: Long) {
        if (mutable.value.regions.none { it.id == id && it.running }) return
        mutable.update { it.copy(pauseReasons = it.pauseReasons + (id to PauseReason.USER), notice = null) }
        // By region id, not title: the title is the name as typed, which can differ from the stored one.
        val jobId = OfflineDownloadJob.jobIdOf(id)
        val job = runner.jobs.value.firstOrNull { it.kind == ActivityKind.DOWNLOADING_MAP && it.id == jobId }
        if (job != null) {
            job.cancel()
        } else {
            // No job to stop (it ended some other way): pause the engine itself.
            scope.launch {
                engine.pause(id)
                refresh()
            }
        }
    }

    /** Ask to delete area [id]; nothing is deleted until [confirmDelete]. */
    fun requestDelete(id: Long) {
        val region = mutable.value.regions.firstOrNull { it.id == id && !it.running } ?: return
        mutable.update { it.copy(deleting = region, notice = null) }
    }

    fun cancelDelete() = mutable.update { it.copy(deleting = null) }

    /** Delete exactly the area the confirmation named. */
    fun confirmDelete() {
        val region = mutable.value.deleting ?: return
        mutable.update { it.copy(deleting = null) }
        scope.launch {
            if (engine.delete(region.id) is EngineResult.Failure) note(OfflineMapsCopy.DELETE_FAILED)
            refresh()
        }
    }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    // ---- internals ----

    private fun note(text: String?) = mutable.update { it.copy(notice = text) }

    private fun proceed(step: PaneStep, request: DownloadRequest) {
        when (step) {
            is PaneStep.Refused -> note(step.note)
            is PaneStep.AskMetered -> mutable.update { it.copy(question = MeteredQuestion(step.asked, request), notice = null) }
            is PaneStep.Launch -> launch(request, step.from)
        }
    }

    /** The one place a download job is started. */
    private fun launch(request: DownloadRequest, from: DownloadState.Downloading) {
        mutable.update { it.copy(notice = null) }
        val existingId = (request as? DownloadRequest.Existing)?.region?.id
        var regionId: Long? = existingId
        if (existingId != null) {
            mutable.update { it.copy(pauseReasons = it.pauseReasons - existingId, failures = it.failures - existingId) }
        }
        val target = when (request) {
            is DownloadRequest.Fresh -> DownloadTarget.New(request.name, from.estimate)
            is DownloadRequest.Existing -> DownloadTarget.Existing(request.region)
        }
        OfflineDownloadJob.launch(
            runner = runner,
            engine = engine,
            target = target,
            from = from,
            nowMillis = nowMillis,
            onState = { moved ->
                val id = regionId
                if (moved is DownloadState.Paused && id != null) {
                    mutable.update { it.copy(pauseReasons = it.pauseReasons + (id to moved.reason)) }
                }
                // A failed run leaves the area stopped in the engine, not paused: say what happened.
                if (moved is DownloadState.Failed && id != null) {
                    mutable.update { it.copy(failures = it.failures + (id to moved.reason)) }
                }
            },
            onStarted = { id ->
                regionId = id
                scope.launch { refresh() }
            },
        )
    }
}
