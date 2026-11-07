package com.fotoxplorr.app.spatial.offline

/** What the pane must do after a tap, decided by [DownloadGuard] and nothing else. */
sealed interface PaneStep {
    /** Nothing starts. [note] is the one sentence to show. */
    data class Refused(val note: String) : PaneStep

    /** Ask about mobile data first; nothing has started. */
    data class AskMetered(val asked: DownloadState.ConfirmMetered) : PaneStep

    /** The guard allowed it: the caller may now start the download job. */
    data class Launch(val from: DownloadState.Downloading) : PaneStep
}

/**
 * Walks a tap on Download, Resume, "Download over mobile data" or "Not now" through
 * [DownloadGuard], so the only way to a [PaneStep.Launch] is the guard's own
 * Start, Confirm or Resume (TRAPS #61, #71). The pane starts a download job from a [PaneStep.Launch]
 * and from nowhere else; opening the pane, scrolling it and a new map view never get here.
 */
object PaneDownloadFlow {

    /** What the guard calls a stored area's size, for a paused area. */
    fun estimateOf(record: RegionRecord): RegionEstimate = RegionEstimate(
        box = record.box,
        minZoom = record.minZoom,
        maxZoom = record.maxZoom,
        tileCount = record.tileCount,
        bytes = record.bytes,
    )

    /** A tap on Download for [box] whose size verdict is [verdict]. */
    fun tapDownload(box: GeoBox, verdict: BudgetVerdict, throttleInstalled: Boolean, metered: Boolean): PaneStep {
        var state: DownloadState = DownloadState.Idle
        for (event in listOf(DownloadEvent.Select(box), DownloadEvent.Estimated(verdict))) {
            state = when (val result = DownloadGuard.reduce(state, event)) {
                is GuardResult.Moved -> result.state
                is GuardResult.Rejected -> return PaneStep.Refused(OfflineMapsCopy.failure(FailureReason.ENGINE))
            }
        }
        if (state is DownloadState.Failed) {
            val tooLarge = (verdict as? BudgetVerdict.TooLarge)?.reason
            return PaneStep.Refused(OfflineMapsCopy.failure(state.reason, tooLarge))
        }
        return stepOf(DownloadGuard.reduce(state, DownloadEvent.Start(throttleInstalled, metered)))
    }

    /** A tap on Resume for a stored, unfinished area. */
    fun tapResume(
        record: RegionRecord,
        progress: RegionProgress,
        reason: PauseReason,
        throttleInstalled: Boolean,
        metered: Boolean,
    ): PaneStep {
        val paused = DownloadState.Paused(estimateOf(record), progress, reason)
        return stepOf(DownloadGuard.reduce(paused, DownloadEvent.Resume(throttleInstalled, metered)))
    }

    /** The person accepted the mobile-data question. */
    fun confirm(asked: DownloadState.ConfirmMetered, throttleInstalled: Boolean): PaneStep =
        stepOf(DownloadGuard.reduce(asked, DownloadEvent.Confirm(throttleInstalled)))

    /** The person declined the question. Nothing starts; the state goes back to where it was asked from. */
    fun decline(asked: DownloadState.ConfirmMetered): DownloadState =
        DownloadGuard.reduce(asked, DownloadEvent.Decline).state

    private fun stepOf(result: GuardResult): PaneStep = when (result) {
        is GuardResult.Rejected -> PaneStep.Refused(
            if (result.reason == RejectReason.THROTTLE_NOT_INSTALLED) {
                OfflineMapsCopy.UNAVAILABLE
            } else {
                OfflineMapsCopy.failure(FailureReason.ENGINE)
            },
        )
        is GuardResult.Moved -> when (val state = result.state) {
            is DownloadState.Downloading -> PaneStep.Launch(state)
            is DownloadState.ConfirmMetered -> PaneStep.AskMetered(state)
            else -> PaneStep.Refused(OfflineMapsCopy.failure(FailureReason.ENGINE))
        }
    }
}
