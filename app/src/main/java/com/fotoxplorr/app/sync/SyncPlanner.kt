package com.fotoxplorr.app.sync

import java.util.Locale

/** One file to send in this run. */
data class PlannedUpload(
    val candidate: SyncCandidate,
    val remotePath: String,
    /** True only when [remotePath] is the half-sent file of an earlier run of ours (the manifest has its `B` line). */
    val overwriteOwnPartial: Boolean,
    /** A file already sent that looks like this one (same size and modified time, other name): hash before uploading. */
    val lookalike: SyncDone? = null,
)

data class SyncPlan(
    val uploads: List<PlannedUpload>,
    /** Candidates the manifest already holds unchanged. */
    val upToDate: Int,
    /** Candidates left for a later run because this run's byte cap was reached. */
    val deferredByCap: Int,
    /** Candidates larger than the whole cap: they cannot go while that cap is set. */
    val tooBigForCap: Int,
) {
    val totalBytes: Long get() = uploads.sumOf { it.candidate.sizeBytes }
}

/**
 * What to send next. Pure: candidates and the manifest in, an ordered list out.
 *
 * Order: newest photo first (taken date, then name), so an interrupted first run has protected the
 * pictures people care about most. A file is "already there" when the manifest has it with the same
 * size and modified time. A changed file is NOT sent over its old copy: it gets a fresh remote name
 * beside it (`name (2).jpg`), because slice 1 never replaces or deletes anything remote. A file with
 * a `B` line and no `D` (a run stopped mid-file) goes to the same remote path again, flagged as our
 * own partial so the provider may overwrite it.
 *
 * The cap counts each photo's own size (encryption adds about 0.01%). Files that do not fit are
 * deferred, not dropped: the next run plans them again.
 */
object SyncPlanner {
    fun plan(
        candidates: List<SyncCandidate>,
        manifest: SyncManifest,
        remoteSuffix: String = "",
        byteCap: Long? = null,
        root: String = RemoteLayout.ROOT,
    ): SyncPlan {
        val ordered = candidates.sortedWith(
            compareByDescending<SyncCandidate> { it.dateTakenMillis }.thenBy { it.key },
        )
        val taken = HashSet(manifest.remotePathsLower())
        val uploads = ArrayList<PlannedUpload>()
        var upToDate = 0
        var deferred = 0
        var tooBig = 0
        var spent = 0L
        var capReached = false
        for (candidate in ordered) {
            if (manifest.isUpToDate(candidate.key, candidate.sizeBytes, candidate.dateModifiedSeconds)) {
                upToDate++
                continue
            }
            if (byteCap != null) {
                if (candidate.sizeBytes > byteCap) {
                    tooBig++
                    continue
                }
                if (capReached || spent + candidate.sizeBytes > byteCap) {
                    capReached = true
                    deferred++
                    continue
                }
            }
            val begun = manifest.begunFor(candidate.key)
            val resumes = begun != null && begun.sizeBytes == candidate.sizeBytes &&
                begun.modifiedSeconds == candidate.dateModifiedSeconds
            val path = if (resumes) {
                begun!!.remotePath
            } else {
                RemoteLayout.unique(RemoteLayout.pathFor(candidate, remoteSuffix, root)) { it.lowercase(Locale.ROOT) in taken }
            }
            taken += path.lowercase(Locale.ROOT)
            spent += candidate.sizeBytes
            uploads += PlannedUpload(
                candidate = candidate,
                remotePath = path,
                overwriteOwnPartial = resumes,
                lookalike = manifest.lookalikesOf(candidate.key, candidate.sizeBytes, candidate.dateModifiedSeconds)
                    .firstOrNull { it.sha256 != null },
            )
        }
        return SyncPlan(uploads, upToDate, deferred, tooBig)
    }
}
