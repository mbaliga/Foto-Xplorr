package com.fotoxplorr.app.media

/**
 * Whether a completed full scan may remove rows it did not see (TRAPS #8: "a partial scan never
 * sweeps", the generalisation of TRAPS #1).
 *
 * Pure and Android-free on purpose, like [ScanPlan.decide] -- the failure mode (photos silently
 * vanishing from the library) is invisible until someone goes looking for one, so this needs to be
 * testable exhaustively rather than trusted by inspection.
 */
object SweepPolicy {
    /**
     * @param catalogueSize how many rows the repository holds right now -- after this pass's own
     *   upserts, before any removal this call might authorise.
     * @param missingCount how many of those rows this pass did NOT see, and would remove if it
     *   swept.
     * @param userRequested a human asked for this refresh -- always allowed to sweep (outside
     *   [partialAccess]), for the same reason [ScanPlan.decide] always honours it in full: a
     *   "refresh" that quietly leaves stale rows behind is a worse failure than one that trusts
     *   the user's own request.
     * @param partialAccess this app currently holds only a limited "selected photos" grant, not
     *   full media access. A scan under that grant can never enumerate the whole library, so
     *   anything it "didn't see" might simply be a photo the user never selected -- not one that
     *   was deleted. Removing it would be wrong every time, so partial access refuses a sweep
     *   unconditionally, even for a user-requested refresh.
     */
    fun shouldSweep(
        catalogueSize: Int,
        missingCount: Int,
        userRequested: Boolean,
        partialAccess: Boolean,
    ): Boolean {
        if (partialAccess) return false
        if (userRequested) return true
        return missingCount <= maxOf(MIN_MISSING_ALLOWANCE, catalogueSize / CATALOGUE_FRACTION_DIVISOR)
    }

    /** The floor on how many missing rows an unattended sweep may remove, even in a tiny library. */
    private const val MIN_MISSING_ALLOWANCE = 200

    /** An unattended sweep may also remove up to this fraction of the catalogue. */
    private const val CATALOGUE_FRACTION_DIVISOR = 4
}
