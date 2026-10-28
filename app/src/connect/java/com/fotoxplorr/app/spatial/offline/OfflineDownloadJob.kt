package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

/** Wording for the job's own messages, one short sentence each. */
internal object OfflineJobCopy {
    fun title(name: String): String = "Downloading $name"

    fun saved(name: String): String = "Saved $name for offline use."
}

/** What a download job runs on: a region already stored (resume), or one to create first. */
internal sealed interface DownloadTarget {
    data class Existing(val region: StoredRegion) : DownloadTarget

    /** A fresh area. [estimate] is the Ready estimate the person saw and tapped Download on. */
    data class New(val name: String, val estimate: RegionEstimate) : DownloadTarget
}

/**
 * Where a download job stopped. [state] is [DownloadState.Done], [DownloadState.Paused] or
 * [DownloadState.Failed]. [regionId] is null when no region was ever created. [refused] is set
 * when the engine would not start (for example the request limit could not be applied); [noRoom]
 * when the region count or total size would have been passed (nothing is created or removed then).
 */
internal data class DownloadEnd(
    val state: DownloadState,
    val regionId: Long?,
    val refused: EngineFailure? = null,
    val noRoom: RoomVerdict? = null,
)

/** A failed download as a job failure, so the activity's outcome collector shows [message]. */
internal class DownloadFailedException(message: String) : Exception(message)

/**
 * One map-area download as a [JobRunner] job: the state machine ([DownloadGuard]) drives the
 * engine, never the other way round.
 *
 * - It runs only because someone tapped Download or Resume: the caller reaches [launch] after the
 *   guard moved to [DownloadState.Downloading] from [DownloadEvent.Start], [DownloadEvent.Confirm]
 *   or [DownloadEvent.Resume] (TRAPS #61, #71). Nothing here starts on its own.
 * - Cancel means pause, never delete: cancelling the job (the shade's or the notification's Cancel,
 *   or the owning scope ending) stops the engine and keeps the region and what it fetched. The state
 *   moves to [PauseReason.USER] and the cancellation then carries on, which the activity treats as a
 *   silent stop.
 * - On any move out of Downloading (done, caps, errors, cancel, the region vanishing) the engine is
 *   stopped; the engine takes the request limit off when its last region stops.
 * - A late reading after that is rejected by the guard and ignored.
 *
 * State and progress are reported through callbacks; where they are kept is the pane's decision.
 * [onStarted] is called once with the region's id, after the engine accepted the start, so a pane
 * can list the region as downloading from then on (before it, the region may exist but fetches nothing). The
 * job outlives the pane, so a pane opened mid-download picks up from [OfflineRegionEngine.list]
 * (`running`) and [OfflineRegionEngine.progress].
 */
internal object OfflineDownloadJob {

    fun launch(
        runner: JobRunner,
        engine: OfflineRegionEngine,
        target: DownloadTarget,
        from: DownloadState.Downloading,
        nowMillis: () -> Long,
        onStarted: (Long) -> Unit = {},
        onState: (DownloadState) -> Unit,
    ): JobHandle {
        val name = when (target) {
            is DownloadTarget.Existing -> target.region.record.name
            is DownloadTarget.New -> target.name
        }
        // A pause must find this job by the region it works on, not by its title: the title is the
        // typed name, which a stored record can read back differently (the 80-character cut).
        var regionId: Long? = (target as? DownloadTarget.Existing)?.region?.id
        var jobId: String? = null
        fun bind() {
            val region = regionId
            val job = jobId
            if (region != null && job != null) jobIds.bind(region, job)
        }
        val handle = runner.launch(OfflineJobCopy.title(name), ActivityKind.DOWNLOADING_MAP) { onProgress ->
            // A cancellation is thrown, not returned: run() has already paused the region.
            outcomeOf(
                run(
                    engine, target, from, nowMillis, onState, onProgress,
                    onStarted = { id ->
                        regionId = id
                        bind()
                        onStarted(id)
                    },
                ),
                name,
            )
        }
        // The job can start (and bind a fresh region) before launch returns, or after: bind both ways.
        jobId = handle.id
        bind()
        return handle
    }

    /**
     * The runner's id of the job downloading region [regionId], or null when none is known. Only a
     * hint: the caller checks it against the runner's list, because a finished job leaves its entry
     * until the next download of that region replaces it.
     */
    fun jobIdOf(regionId: Long): String? = jobIds.idOf(regionId)

    /** Region id to runner job id, process-wide: a pane opened later must still find a running job. */
    private val jobIds = object {
        private val byRegion = HashMap<Long, String>()

        @Synchronized
        fun bind(regionId: Long, jobId: String) {
            byRegion[regionId] = jobId
        }

        @Synchronized
        fun idOf(regionId: Long): String? = byRegion[regionId]
    }

    /** The job's result for the activity: a notice for done and for a paused connection, a failure for the rest. */
    fun outcomeOf(end: DownloadEnd, name: String): Result<String> = when (val state = end.state) {
        is DownloadState.Done -> Result.success(OfflineJobCopy.saved(name))
        is DownloadState.Paused -> when (state.reason) {
            PauseReason.CONNECTION -> Result.success(OfflineMapsCopy.paused(state.reason))
            // The person paused, or the engine did not start: the pane shows it, no notice.
            else -> if (end.refused == EngineFailure.THROTTLE_NOT_INSTALLED) {
                Result.failure(DownloadFailedException(OfflineMapsCopy.UNAVAILABLE))
            } else {
                Result.success("")
            }
        }
        is DownloadState.Failed -> Result.failure(
            DownloadFailedException(
                if (end.noRoom != null) OfflineMapsCopy.ROOM_NEEDED else OfflineMapsCopy.failure(state.reason),
            ),
        )
        else -> Result.success("")
    }

    /**
     * Runs the download to its end. Visible to tests; the job calls it. Rethrows a cancellation
     * after pausing the region and reporting [PauseReason.USER].
     */
    suspend fun run(
        engine: OfflineRegionEngine,
        target: DownloadTarget,
        from: DownloadState.Downloading,
        nowMillis: () -> Long,
        onState: (DownloadState) -> Unit,
        onProgress: (Float?) -> Unit,
        onStarted: (Long) -> Unit = {},
    ): DownloadEnd {
        var state: DownloadState = from
        fun move(event: DownloadEvent): Boolean {
            when (val result = DownloadGuard.reduce(state, event)) {
                is GuardResult.Moved -> {
                    state = result.state
                    onState(state)
                    when (val now = state) {
                        is DownloadState.Downloading -> onProgress(now.progress.fraction)
                        is DownloadState.Done -> onProgress(1f)
                        else -> Unit
                    }
                    return true
                }
                is GuardResult.Rejected -> return false
            }
        }
        onState(state)
        onProgress(from.progress.fraction)

        val region = when (val resolved = resolve(engine, target, nowMillis)) {
            is Resolved.Ready -> resolved.region
            is Resolved.NoRoom -> {
                move(DownloadEvent.EngineFailed)
                return DownloadEnd(state, regionId = null, noRoom = resolved.verdict)
            }
            is Resolved.Refused -> {
                move(DownloadEvent.EngineFailed)
                return DownloadEnd(state, regionId = null, refused = resolved.failure)
            }
        }

        try {
            when (val started = engine.resume(region.id)) {
                is EngineResult.Failure -> {
                    // Nothing started. A refused start leaves the region as it was: paused.
                    move(if (started.reason == EngineFailure.ENGINE_ERROR) DownloadEvent.EngineFailed else DownloadEvent.Cancel)
                    return DownloadEnd(state, region.id, refused = started.reason)
                }
                is EngineResult.Ok -> onStarted(region.id)
            }

            engine.progress(region.id)
                .takeWhile { reading ->
                    move(DownloadEvent.Progress(reading))
                    state is DownloadState.Downloading
                }
                .collect { }
            // The feed ended while still downloading: the region was deleted underneath the job.
            if (state is DownloadState.Downloading) move(DownloadEvent.EngineFailed)
        } finally {
            withContext(NonCancellable) {
                // Cancelled, or an exception: that is a pause, never a delete.
                if (state is DownloadState.Downloading) move(DownloadEvent.Cancel)
                // A finished region has already stopped itself; every other end stops the engine.
                if (state !is DownloadState.Done) engine.pause(region.id)
            }
        }
        return DownloadEnd(state, region.id)
    }

    private sealed interface Resolved {
        data class Ready(val region: StoredRegion) : Resolved
        data class NoRoom(val verdict: RoomVerdict) : Resolved
        data class Refused(val failure: EngineFailure) : Resolved
    }

    private suspend fun resolve(
        engine: OfflineRegionEngine,
        target: DownloadTarget,
        nowMillis: () -> Long,
    ): Resolved = when (target) {
        is DownloadTarget.Existing -> Resolved.Ready(target.region)
        is DownloadTarget.New -> {
            when (val listed = engine.list()) {
                is EngineResult.Failure -> Resolved.Refused(listed.reason)
                is EngineResult.Ok -> {
                    // Defence in depth: the pane checks this before enabling Download, but the job
                    // is the one place that creates a region, so the limits are enforced here too.
                    // A refusal creates and removes nothing (TRAPS #64).
                    val verdict = RegionLedger.checkRoom(listed.value.map { it.record }, target.estimate.bytes)
                    if (verdict != RoomVerdict.Ok) {
                        Resolved.NoRoom(verdict)
                    } else {
                        val now = nowMillis()
                        val estimate = target.estimate
                        val record = RegionRecord(
                            name = target.name,
                            box = estimate.box,
                            minZoom = estimate.minZoom,
                            maxZoom = estimate.maxZoom,
                            styleId = MapLibreRegionEngine.LIBERTY_STYLE_ID,
                            createdAtMillis = now,
                            lastUsedMillis = now,
                            bytes = estimate.bytes,
                            tileCount = estimate.tileCount,
                        )
                        when (val created = engine.create(record)) {
                            is EngineResult.Ok -> Resolved.Ready(created.value)
                            is EngineResult.Failure -> Resolved.Refused(created.reason)
                        }
                    }
                }
            }
        }
    }
}
