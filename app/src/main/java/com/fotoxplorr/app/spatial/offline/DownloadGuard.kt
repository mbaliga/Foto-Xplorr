package com.fotoxplorr.app.spatial.offline

/** Why a download is paused. A pause keeps the region and what it fetched. */
enum class PauseReason {
    /** The person cancelled. Cancel means pause, never delete. */
    USER,

    /** Repeated connection errors; resumable when the network is back. */
    CONNECTION,

    /** The app was relaunched and found the region partly downloaded. */
    RESTARTED,
}

/** Why a flow ended without a finished region. */
enum class FailureReason { TOO_LARGE, NO_AREA, OVER_CAP, SERVER, ENGINE }

/** Why an event was refused. */
enum class RejectReason {
    /** The event is not legal in the current state. */
    NOT_ALLOWED_NOW,

    /** A download was asked for while the engine's parallel-request cap is not in force. */
    THROTTLE_NOT_INSTALLED,
}

sealed interface DownloadState {
    data object Idle : DownloadState

    /** An area was chosen and its size is being worked out. */
    data class Estimating(val box: GeoBox) : DownloadState

    /** Sized and within the caps; waiting for an explicit tap on Download. */
    data class Ready(val estimate: RegionEstimate) : DownloadState

    /**
     * Waiting for the person to accept using a metered connection. [resumeFrom] is the progress to
     * continue from when this question came from Resume, null for a fresh download.
     */
    data class ConfirmMetered(val estimate: RegionEstimate, val resumeFrom: RegionProgress?) : DownloadState

    data class Downloading(
        val estimate: RegionEstimate,
        val progress: RegionProgress,
        /** Consecutive server or connection errors with no progress between them. */
        val errorStreak: Int = 0,
    ) : DownloadState

    data class Paused(val estimate: RegionEstimate, val progress: RegionProgress, val reason: PauseReason) : DownloadState

    data class Done(val estimate: RegionEstimate, val progress: RegionProgress) : DownloadState

    data class Failed(val reason: FailureReason, val estimate: RegionEstimate? = null) : DownloadState
}

sealed interface DownloadEvent {
    /** An area was chosen. */
    data class Select(val box: GeoBox) : DownloadEvent

    /** The size of the chosen area is known. */
    data class Estimated(val verdict: BudgetVerdict) : DownloadEvent

    /** The person tapped Download. [metered] is read at tap time by the Connect code. */
    data class Start(val throttleInstalled: Boolean, val metered: Boolean) : DownloadEvent

    /** The person accepted the metered-connection question. */
    data class Confirm(val throttleInstalled: Boolean) : DownloadEvent

    /** The person declined the metered-connection question. */
    data object Decline : DownloadEvent

    /** The person tapped Resume on a paused region. */
    data class Resume(val throttleInstalled: Boolean, val metered: Boolean) : DownloadEvent

    /** A new reading from the engine. */
    data class Progress(val progress: RegionProgress) : DownloadEvent

    /** The person tapped Cancel. */
    data object Cancel : DownloadEvent

    /** The engine reported that it cannot continue. */
    data object EngineFailed : DownloadEvent

    /** After a relaunch: a stored region that is not finished. Never starts a download. */
    data class Restored(val estimate: RegionEstimate, val progress: RegionProgress) : DownloadEvent

    /** Forget the current flow and go back to idle. Does not delete a stored region. */
    data object Dismiss : DownloadEvent
}

sealed interface GuardResult {
    val state: DownloadState

    data class Moved(override val state: DownloadState) : GuardResult

    /** The event was refused; [state] is the unchanged state. */
    data class Rejected(override val state: DownloadState, val reason: RejectReason) : GuardResult
}

/**
 * The legal moves of one region's download, as a pure function of (state, event).
 *
 * ```
 * Idle -Select-> Estimating -Estimated-> Ready -Start-> Downloading -Progress(complete)-> Done
 *                     |                    |  \-metered-> ConfirmMetered -Confirm-> Downloading
 *                     |                    |                    \-Decline/Cancel-> Ready
 *                     \-too large-> Failed |
 * Downloading -Cancel-> Paused -Resume-> Downloading (or ConfirmMetered when metered)
 * Downloading -caps or repeated errors-> Failed / Paused
 * ```
 *
 * Download is legal only from [DownloadState.Ready] or [DownloadState.ConfirmMetered], and only
 * when the engine's throttle is installed; a [DownloadState.Downloading] can be reached by nothing
 * else. The caller must stop the engine on any move into [DownloadState.Paused] or
 * [DownloadState.Failed], and call [OfflineRegionEngine.resume] only after a move into
 * [DownloadState.Downloading] that came from a tap.
 *
 * A late [DownloadEvent.Progress] after a pause is [GuardResult.Rejected] and harmless: ignore it.
 */
object DownloadGuard {

    fun canStart(state: DownloadState, throttleInstalled: Boolean): Boolean =
        throttleInstalled && (state is DownloadState.Ready || state is DownloadState.ConfirmMetered)

    fun canResume(state: DownloadState, throttleInstalled: Boolean): Boolean =
        throttleInstalled && state is DownloadState.Paused && !isOverCap(state.progress)

    /**
     * True when [progress] is past a cap of ours. A region that stopped there stays that way: the
     * numbers do not go down, so a resume would fetch a few more resources, trip the guard again
     * and spend the provider's allowance for nothing. It is judged from the stored numbers, so it
     * holds after a relaunch too.
     */
    fun isOverCap(progress: RegionProgress): Boolean =
        progress.completedBytes > OfflineMapLimits.MAX_BYTES || progress.completedTiles > OfflineMapLimits.MAX_TILES

    fun reduce(state: DownloadState, event: DownloadEvent): GuardResult = when (event) {
        is DownloadEvent.Select -> when (state) {
            is DownloadState.Downloading, is DownloadState.Paused -> refuse(state)
            else -> moved(DownloadState.Estimating(event.box))
        }

        is DownloadEvent.Estimated -> when (state) {
            is DownloadState.Estimating -> moved(
                when (val verdict = event.verdict) {
                    is BudgetVerdict.Fits -> DownloadState.Ready(verdict.estimate)
                    is BudgetVerdict.Reduced -> DownloadState.Ready(verdict.estimate)
                    is BudgetVerdict.TooLarge -> DownloadState.Failed(FailureReason.TOO_LARGE, verdict.floorEstimate)
                    BudgetVerdict.NoArea -> DownloadState.Failed(FailureReason.NO_AREA)
                },
            )
            else -> refuse(state)
        }

        is DownloadEvent.Start -> when {
            state !is DownloadState.Ready -> refuse(state)
            !event.throttleInstalled -> refuse(state, RejectReason.THROTTLE_NOT_INSTALLED)
            event.metered -> moved(DownloadState.ConfirmMetered(state.estimate, resumeFrom = null))
            else -> moved(DownloadState.Downloading(state.estimate, RegionProgress.EMPTY))
        }

        is DownloadEvent.Confirm -> when {
            state !is DownloadState.ConfirmMetered -> refuse(state)
            !event.throttleInstalled -> refuse(state, RejectReason.THROTTLE_NOT_INSTALLED)
            else -> moved(DownloadState.Downloading(state.estimate, state.resumeFrom ?: RegionProgress.EMPTY))
        }

        DownloadEvent.Decline -> when (state) {
            is DownloadState.ConfirmMetered -> moved(backFromQuestion(state))
            else -> refuse(state)
        }

        is DownloadEvent.Resume -> when {
            state !is DownloadState.Paused -> refuse(state)
            isOverCap(state.progress) -> refuse(state)
            !event.throttleInstalled -> refuse(state, RejectReason.THROTTLE_NOT_INSTALLED)
            event.metered -> moved(DownloadState.ConfirmMetered(state.estimate, resumeFrom = state.progress))
            else -> moved(DownloadState.Downloading(state.estimate, state.progress))
        }

        is DownloadEvent.Progress -> when (state) {
            is DownloadState.Downloading -> onProgress(state, event.progress)
            else -> refuse(state)
        }

        DownloadEvent.Cancel -> when (state) {
            is DownloadState.Downloading -> moved(DownloadState.Paused(state.estimate, state.progress, PauseReason.USER))
            is DownloadState.Estimating, is DownloadState.Ready -> moved(DownloadState.Idle)
            is DownloadState.ConfirmMetered -> moved(backFromQuestion(state))
            else -> refuse(state)
        }

        DownloadEvent.EngineFailed -> when (state) {
            is DownloadState.Downloading -> moved(DownloadState.Failed(FailureReason.ENGINE, state.estimate))
            else -> refuse(state)
        }

        is DownloadEvent.Restored -> when (state) {
            DownloadState.Idle -> moved(DownloadState.Paused(event.estimate, event.progress, PauseReason.RESTARTED))
            else -> refuse(state)
        }

        DownloadEvent.Dismiss -> when (state) {
            is DownloadState.Downloading -> refuse(state)
            else -> moved(DownloadState.Idle)
        }
    }

    private fun onProgress(state: DownloadState.Downloading, progress: RegionProgress): GuardResult {
        // A finished download is finished, whatever it weighed: there is nothing left to stop.
        if (progress.complete) return moved(DownloadState.Done(state.estimate, progress))

        if (isOverCap(progress)) {
            return moved(DownloadState.Failed(FailureReason.OVER_CAP, state.estimate))
        }

        val streak = when (progress.error) {
            null -> if (progress.completedResources > state.progress.completedResources) 0 else state.errorStreak
            DownloadErrorKind.NOT_FOUND -> state.errorStreak
            else -> state.errorStreak + 1
        }
        if (streak >= OfflineMapLimits.MAX_CONSECUTIVE_ERRORS) {
            return moved(
                when (progress.error) {
                    DownloadErrorKind.CONNECTION -> DownloadState.Paused(state.estimate, progress, PauseReason.CONNECTION)
                    DownloadErrorKind.SERVER -> DownloadState.Failed(FailureReason.SERVER, state.estimate)
                    else -> DownloadState.Failed(FailureReason.ENGINE, state.estimate)
                },
            )
        }
        return moved(state.copy(progress = progress, errorStreak = streak))
    }

    /** Leaving the metered question without downloading: back to where it came from. */
    private fun backFromQuestion(state: DownloadState.ConfirmMetered): DownloadState =
        state.resumeFrom?.let { DownloadState.Paused(state.estimate, it, PauseReason.USER) }
            ?: DownloadState.Ready(state.estimate)

    private fun moved(state: DownloadState): GuardResult = GuardResult.Moved(state)

    private fun refuse(state: DownloadState, reason: RejectReason = RejectReason.NOT_ALLOWED_NOW): GuardResult =
        GuardResult.Rejected(state, reason)
}
