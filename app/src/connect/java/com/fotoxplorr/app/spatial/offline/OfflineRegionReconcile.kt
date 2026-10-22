package com.fotoxplorr.app.spatial.offline

/**
 * Turns what the engine lists after a relaunch into what the pane shows.
 *
 * A download does not survive the process (a [com.fotoxplorr.app.jobs.JobRunner] job dies with it),
 * but the region and what it fetched persist in the map library's database. So a region that is not
 * complete and not running comes back as [DownloadEvent.Restored], which the guard turns into a
 * paused entry that says "tap resume". Nothing here, or in [DownloadEvent.Restored], starts a
 * download.
 */
internal object OfflineRegionReconcile {

    /** The size the region was planned at, as the guard's state wants it. */
    fun estimateOf(record: RegionRecord): RegionEstimate = PaneDownloadFlow.estimateOf(record)

    /** The event for [region] after a relaunch, or null when it is finished or is downloading right now. */
    fun restoredEvent(region: StoredRegion): DownloadEvent.Restored? =
        if (region.running || region.progress.complete) {
            null
        } else {
            DownloadEvent.Restored(estimateOf(region.record), region.progress)
        }

    /** Regions to show as paused, each with its event, least recently used first. */
    fun unfinished(regions: List<StoredRegion>): List<Pair<StoredRegion, DownloadEvent.Restored>> =
        regions.mapNotNull { region -> restoredEvent(region)?.let { region to it } }
            .sortedWith(compareBy({ it.first.record.lastUsedMillis }, { it.first.record.createdAtMillis }, { it.first.record.name }))

    /** Finished regions, least recently used first (the order to offer them for deletion). */
    fun finished(regions: List<StoredRegion>): List<StoredRegion> {
        val done = regions.filter { it.progress.complete }
        val order = RegionLedger.oldestUnusedFirst(done.map { it.record })
        return order.mapNotNull { record -> done.firstOrNull { it.record == record } }
    }
}
