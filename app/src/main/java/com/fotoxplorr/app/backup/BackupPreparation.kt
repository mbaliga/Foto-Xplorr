package com.fotoxplorr.app.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A run, decided but not started: the destination as it was found, the plan, and the cipher
 * limits to write with. The preflight card is drawn from this; the job executes it.
 */
class BackupPreparation(
    val loaded: LoadedSet,
    val plan: BackupPlan,
    val limits: PackLimits,
    val options: BackupOptions,
    /** How many phone files the plan looked at (everything the catalogue offered). */
    val candidateCount: Int,
) {
    /** True when the folder holds no finished backup yet. */
    val isNewSet: Boolean get() = loaded.manifest == null && loaded.recovered.isEmpty()

    /** Bytes the new packs will occupy in the destination, with the cipher's own framing. */
    val estimatedBytes: Long get() = plan.packs.sumOf { it.fileBytes }

    val nextGeneration: Int get() = loaded.nextGeneration
}

object BackupPreparer {
    /**
     * Opens the set, notices what an interrupted run left, and plans. The destination is listed
     * once more when the first listing makes nearly everything look lost ([BackupPlan.destinationLooksUnlisted]):
     * a listing that failed in a quiet way must not be trusted to rewrite the snapshot.
     *
     * [packLimits] are the pack sizes (the defaults are 1 GiB packs under a 4 GiB hard cap; tests use small ones).
     *
     * Files that look like moved ones (same name and size as a vanished record) get a
     * [QuickHash] read from [source] so a renamed album is recognised instead of copied again.
     *
     * [locationReadable] says the photos' own GPS can be read now ([BackupPlanner]): files an earlier
     * run stored without it are then planned for another copy.
     */
    suspend fun prepare(
        destination: BackupDestination,
        keys: BackupKeys,
        candidates: List<BackupCandidate>,
        options: BackupOptions,
        source: BackupSource,
        packLimits: PackLimits = PackLimits(),
        locationReadable: Boolean = false,
    ): BackupPreparation {
        var loaded = BackupSetLoader.load(destination, keys)
        var plan = planFor(loaded, candidates, options, limitsFor(loaded, packLimits), locationReadable)
        if (plan.destinationLooksUnlisted) {
            loaded = BackupSetLoader.load(destination, keys)
            plan = planFor(loaded, candidates, options, limitsFor(loaded, packLimits), locationReadable)
        }
        val limits = limitsFor(loaded, packLimits)

        val needing = BackupPlanner.candidatesNeedingQuickHash(loaded.known, candidates, options, loaded.packFileNames, locationReadable)
        var working = candidates
        if (needing.isNotEmpty()) {
            val hashes = HashMap<Long, String>()
            for (candidate in needing) {
                currentCoroutineContext().ensureActive()
                source.quickHashOf(candidate)?.let { hashes[candidate.mediaId] = it }
            }
            if (hashes.isNotEmpty()) {
                working = candidates.map { c -> hashes[c.mediaId]?.let { c.copy(quickHash = it) } ?: c }
                plan = planFor(loaded, working, options, limits, locationReadable)
            }
        }
        return BackupPreparation(loaded, plan, limits, options, candidates.size)
    }

    /** A set keeps the segment size it was started with; [wanted] decides the pack sizes and the segment size of a new set. */
    private fun limitsFor(loaded: LoadedSet, wanted: PackLimits): PackLimits =
        loaded.manifest?.header?.segmentSize?.let { wanted.copy(segmentSize = it) } ?: wanted

    private fun planFor(loaded: LoadedSet, candidates: List<BackupCandidate>, options: BackupOptions, limits: PackLimits, locationReadable: Boolean): BackupPlan =
        BackupPlanner.plan(
            known = loaded.known,
            candidates = candidates,
            options = options,
            existingPacks = loaded.packFileNames,
            limits = limits,
            reservedPackNumbers = loaded.reservedPackNumbers,
            locationReadable = locationReadable,
        )
}
