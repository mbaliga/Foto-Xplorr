package com.fotoxplorr.app.media

/**
 * Decides how much work a rescan actually has to do.
 *
 * ## Why this exists
 *
 * Taking a single screenshot used to restart indexing from zero: "Indexing 3456 of 21526"
 * would drop back to 0 and climb again. Three things combined to cause it —
 *
 *  1. `MediaStoreChangeObserver` fires on *any* MediaStore change, and one screenshot emits
 *     several (insert, thumbnail, metadata);
 *  2. the rescan re-queried the entire collection and re-emitted progress from 0 with the
 *     whole library as the denominator; and
 *  3. it was driven by a `LaunchedEffect` keyed on a counter, so each new change **cancelled
 *     the in-flight scan** and started over — under churn it could never finish.
 *
 * The fix is this type: a scan is either the FULL first pass or a DELTA covering only what
 * changed since the last completed pass. Keeping the decision here — pure, no Android, no
 * coroutines — means it can be tested exhaustively, which matters because the failure mode
 * (an index that silently misses photos) is invisible until someone goes looking for one.
 *
 * ## The watermark
 *
 * MediaStore's `DATE_MODIFIED` is in **seconds**, and rows can appear with a timestamp at or
 * a little before the moment we finish scanning. A watermark used as a strict `>` bound
 * would drop those rows forever. So [deltaSince] deliberately rewinds by
 * [WATERMARK_REWIND_SECONDS] and the query uses `>=`: re-seeing a handful of already-known
 * rows is free (the upsert is idempotent and the recognition pass skips anything already
 * indexed), whereas missing one is permanent.
 */
sealed interface ScanPlan {

    /** Re-read everything. First run, an explicit user refresh, or a watermark we can't trust. */
    data object Full : ScanPlan

    /**
     * Read only rows modified at or after [sinceSeconds], then reconcile deletions against
     * the ids we already hold.
     */
    data class Delta(val sinceSeconds: Long) : ScanPlan

    companion object {

        /**
         * How far to rewind the watermark. MediaStore timestamps are second-granular and a
         * write can land in the same second the previous scan completed, so a strict bound
         * would lose it. Ten seconds costs a few redundant rows and closes the hole.
         */
        const val WATERMARK_REWIND_SECONDS: Long = 10L

        /**
         * Choose a plan.
         *
         * @param lastCompletedSeconds watermark from the last *completed* scan, 0 if none.
         * @param knownAssetCount how many assets the repository already holds.
         * @param userRequested true when a human asked for a refresh — always honoured in
         *   full, because "refresh" that quietly does nothing is worse than a slow refresh.
         */
        fun decide(
            lastCompletedSeconds: Long,
            knownAssetCount: Int,
            userRequested: Boolean,
        ): ScanPlan = when {
            userRequested -> Full
            // Nothing indexed yet — a delta would produce an empty library.
            knownAssetCount == 0 -> Full
            // No usable watermark (first run after an upgrade, or a corrupted value).
            lastCompletedSeconds <= 0L -> Full
            else -> Delta(sinceSeconds = deltaSince(lastCompletedSeconds))
        }

        /** The rewound lower bound for a delta query. Never negative. */
        fun deltaSince(lastCompletedSeconds: Long): Long =
            (lastCompletedSeconds - WATERMARK_REWIND_SECONDS).coerceAtLeast(0L)
    }
}

/**
 * What a completed scan changed, so the UI can say something true about it.
 *
 * The banner used to render `Progress.scanned of Progress.discovered`, which after a delta
 * scan would have been a meaningless "3 of 3" and before the fix was a full-library
 * re-count. [newOrChanged] is the only number worth showing a person.
 */
data class ScanOutcome(
    val plan: ScanPlan,
    val newOrChanged: Int,
    val removed: Int,
    val totalAfter: Int,
)
