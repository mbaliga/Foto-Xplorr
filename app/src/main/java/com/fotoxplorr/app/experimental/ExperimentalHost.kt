package com.fotoxplorr.app.experimental

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId

/*
 * The pure decisions of the Experimental host, apart from the composables so a plain JUnit test
 * can hold them: which entry is open, what is saved, and in what order the hub lists things.
 */

/** The groups that have at least one entry, in [ExperimentGroup] order, each with its entries in list order. */
internal fun groupedEntries(entries: List<ExperimentEntry>): List<Pair<ExperimentGroup, List<ExperimentEntry>>> =
    ExperimentGroup.entries.mapNotNull { group ->
        entries.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }

/**
 * The entry to show for a saved or tapped [id], or null for the hub. An id nobody registers (an
 * entry removed or renamed since it was saved) and an entry this device cannot run both give the
 * hub, never a crash and never a blank screen.
 */
internal fun entryToShow(
    entries: List<ExperimentEntry>,
    id: String?,
    isAvailable: (ExperimentEntry) -> Boolean,
): ExperimentEntry? = id?.let { wanted -> entries.firstOrNull { it.id == wanted } }?.takeIf(isAvailable)

/**
 * What to write into saved state for the open [id]: the id itself when the entry opts into being
 * reopened, otherwise nothing, so a recreation or a process death lands on the hub.
 */
internal fun idToSave(entries: List<ExperimentEntry>, id: String?): String? =
    id?.takeIf { wanted -> entries.firstOrNull { it.id == wanted }?.restorable == true }

/** The ids that appear more than once in [entries]; the host refuses a list that has any. */
internal fun duplicateIds(entries: List<ExperimentEntry>): List<String> =
    entries.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.toList()

/**
 * Whether a location read should start now: nothing has been scanned and no pass is running. The
 * caller asks at most once per time the experiment opens (a library whose locations cannot be read
 * at all would otherwise start a new pass the moment each one ended).
 */
internal fun shouldStartLocationIndex(geoState: GeoIndexState, alreadyAsked: Boolean): Boolean =
    !alreadyAsked && geoState.scannedCount == 0 && !geoState.isIndexing

/**
 * The photos an experiment is given. While "Blur sensitive" is on, a photo flagged sensitive is
 * left out: the grid blurs such a photo, but an experiment draws it at its own size (a card cover,
 * a picker, a wall, a node) and has no blur of its own, so the safe reading of the setting here is
 * that the photo is not offered. With the setting off, or nothing flagged, [assets] is returned as
 * it is. [ExperimentEnv.mayExport] follows the same list, so a flagged photo is not read either.
 */
internal fun assetsForExperiments(
    assets: List<MediaAsset>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
): List<MediaAsset> =
    if (!blurSensitive || sensitiveIds.isEmpty()) assets else assets.filterNot { it.id in sensitiveIds }
