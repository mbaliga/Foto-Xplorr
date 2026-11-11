package com.fotoxplorr.app.sync

/** What a run has done so far. Counts of files and bytes only: never a name (job titles and the shade show no names). */
data class SyncTally(
    val uploaded: Int = 0,
    val uploadedBytes: Long = 0,
    /** Found at the destination already (a lost manifest, a rename) and recorded without sending. */
    val adopted: Int = 0,
    val upToDate: Int = 0,
    /** Left out because the browsable list no longer allows them, or the file is gone. */
    val skipped: Int = 0,
    val failed: Int = 0,
    val deferredByCap: Int = 0,
    val tooBigForCap: Int = 0,
)

/** The states a run moves through. [SyncRunMachine] is the only thing that changes them. */
sealed interface SyncRunState {
    data object Idle : SyncRunState

    /** Reading the library and the manifest, working out the key. No bytes move yet. */
    data object Preparing : SyncRunState

    data class Running(
        val filesDone: Int,
        val filesTotal: Int,
        /** Bytes of files already settled (sent, adopted or skipped). */
        val bytesCommitted: Long,
        /** Bytes of the file being sent that the destination has accepted so far. */
        val bytesInFile: Long,
        val bytesTotal: Long,
        val tally: SyncTally,
    ) : SyncRunState {
        val bytesDone: Long get() = (bytesCommitted + bytesInFile).coerceAtMost(bytesTotal)

        /** 0..1, or null while the total is unknown/zero. */
        val progress: Float? get() = if (bytesTotal > 0) (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat() else null
    }

    /** Stopped by the person or the system; everything recorded is kept and a new run continues. */
    data class Paused(val tally: SyncTally, val filesLeft: Int) : SyncRunState

    data class Finished(val tally: SyncTally) : SyncRunState

    /** Stopped by a problem that will not clear by retrying now. [failure] is what the person is told about. */
    data class Failed(val failure: SyncFailure, val message: String, val tally: SyncTally) : SyncRunState
}

sealed interface SyncRunEvent {
    data object Start : SyncRunEvent
    data class Planned(val files: Int, val bytes: Long, val tally: SyncTally) : SyncRunEvent
    /** [inCurrentFile] is absolute (the whole file so far), not a delta. */
    data class Bytes(val inCurrentFile: Long) : SyncRunEvent
    /** A file is settled: [bytes] is its planned size, whether it was sent, adopted or skipped. */
    data class FileFinished(val bytes: Long, val tally: SyncTally) : SyncRunEvent
    data class Counted(val tally: SyncTally) : SyncRunEvent
    data object Cancelled : SyncRunEvent
    data object Completed : SyncRunEvent
    data class Aborted(val failure: SyncFailure, val message: String) : SyncRunEvent
}

/** The run's transitions, pure so the allowed ones are pinned by a test. Anything not listed leaves the state alone. */
object SyncRunMachine {
    fun reduce(state: SyncRunState, event: SyncRunEvent): SyncRunState = when (event) {
        SyncRunEvent.Start -> when (state) {
            is SyncRunState.Preparing, is SyncRunState.Running -> state
            else -> SyncRunState.Preparing
        }
        is SyncRunEvent.Planned -> when (state) {
            is SyncRunState.Preparing -> SyncRunState.Running(0, event.files, 0, 0, event.bytes, event.tally)
            else -> state
        }
        is SyncRunEvent.Bytes -> when (state) {
            is SyncRunState.Running -> state.copy(bytesInFile = event.inCurrentFile)
            else -> state
        }
        is SyncRunEvent.FileFinished -> when (state) {
            is SyncRunState.Running -> state.copy(
                filesDone = state.filesDone + 1,
                bytesCommitted = state.bytesCommitted + event.bytes,
                bytesInFile = 0,
                tally = event.tally,
            )
            else -> state
        }
        is SyncRunEvent.Counted -> when (state) {
            is SyncRunState.Running -> state.copy(tally = event.tally)
            else -> state
        }
        SyncRunEvent.Cancelled -> when (state) {
            is SyncRunState.Running -> SyncRunState.Paused(state.tally, (state.filesTotal - state.filesDone).coerceAtLeast(0))
            is SyncRunState.Preparing -> SyncRunState.Idle
            else -> state
        }
        SyncRunEvent.Completed -> when (state) {
            is SyncRunState.Running -> SyncRunState.Finished(state.tally)
            is SyncRunState.Preparing -> SyncRunState.Finished(SyncTally())
            else -> state
        }
        is SyncRunEvent.Aborted -> when (state) {
            is SyncRunState.Running -> SyncRunState.Failed(event.failure, event.message, state.tally)
            is SyncRunState.Preparing -> SyncRunState.Failed(event.failure, event.message, SyncTally())
            else -> state
        }
    }
}
