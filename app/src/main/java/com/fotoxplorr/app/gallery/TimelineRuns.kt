package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.isStampRung
import com.fotoxplorr.app.media.MediaAsset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Cuts [assets] into groups wherever the day or month of [timelineMillis] CHANGES, keeping the
 * list exactly as given -- no re-sort, and no merging of two stretches that happen to share a month.
 *
 * That is the difference from [timelineGroups], which buckets by date and sorts the buckets newest
 * first. A grid has to draw the photos in the order the sort chose (oldest first, or by name),
 * and headers then follow the photos instead of reordering them. The price is that one month can
 * head several runs when the order is not a date order; each run therefore carries its index in
 * its key (`run:<index>:<yyyy-MM>`), so two headers for the same month can never share a lazy-grid
 * key -- a duplicate key throws.
 *
 * Judged on [timelineMillis], the date taken else the file's modified time, so the headers, the
 * edge scrubber and the time range all agree about which month a photo belongs to.
 *
 * [TimelineGrouping.NONE] is one group holding everything. The order of [assets] is the order of
 * the groups' contents, concatenated.
 */
internal fun timelineRuns(
    assets: List<MediaAsset>,
    grouping: TimelineGrouping,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<TimelineGroup> {
    if (grouping == TimelineGrouping.NONE) {
        return listOf(TimelineGroup("all", "All media", assets))
    }
    if (assets.isEmpty()) return emptyList()
    val titleFormat = DateTimeFormatter.ofPattern(
        if (grouping == TimelineGrouping.DAY) "EEEE, d MMMM yyyy" else "MMMM yyyy",
        locale,
    )
    val runs = ArrayList<TimelineGroup>()
    var runStart = 0
    var runBucket = bucketOf(assets[0], grouping, zoneId)
    var runDate = dateOf(assets[0], zoneId)

    fun close(endExclusive: Int) {
        runs += TimelineGroup(
            key = "run:${runs.size}:${bucketKey(runDate, grouping)}",
            title = runDate.format(titleFormat),
            assets = assets.subList(runStart, endExclusive),
        )
    }

    for (index in 1 until assets.size) {
        val date = dateOf(assets[index], zoneId)
        val bucket = bucketOf(date, grouping)
        if (bucket != runBucket) {
            close(index)
            runStart = index
            runBucket = bucket
            runDate = date
        }
    }
    close(assets.size)
    return runs
}

private fun dateOf(asset: MediaAsset, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(asset.timelineMillis).atZone(zoneId).toLocalDate()

private fun bucketOf(asset: MediaAsset, grouping: TimelineGrouping, zoneId: ZoneId): Long =
    bucketOf(dateOf(asset, zoneId), grouping)

/** A number that is equal for two dates exactly when they share a day (or a month). */
private fun bucketOf(date: LocalDate, grouping: TimelineGrouping): Long =
    if (grouping == TimelineGrouping.DAY) date.toEpochDay() else date.year * 12L + date.monthValue

/** `2026-09` or `2026-09-14`, built by hand so a locale with other digits cannot change a key. */
private fun bucketKey(date: LocalDate, grouping: TimelineGrouping): String {
    val month = date.monthValue.toString().padStart(2, '0')
    val year = date.year.toString().padStart(4, '0')
    return if (grouping == TimelineGrouping.DAY) {
        "$year-$month-${date.dayOfMonth.toString().padStart(2, '0')}"
    } else {
        "$year-$month"
    }
}

/**
 * Whether the grid draws month headers (and so is laid out as stamps under them).
 *
 * Only the stamp rung has them, and only when the photos run in date order: with NAME or SIZE the
 * months would interleave and a header would be repeated every few tiles, so the stamps are drawn
 * flat. A masonry grid has no headers at all (its staggered layout has no full-width item to hang
 * one on) and never reaches the stamp rung.
 */
internal fun stampHeadersShown(rung: Int, sort: GallerySort, masonry: Boolean): Boolean =
    isStampRung(rung) && !masonry && (sort == GallerySort.NEWEST || sort == GallerySort.OLDEST)

/**
 * What the one grid draws, in draw order: the groups (a single headerless group on the column
 * rungs, month runs on the stamp rung) and whether each group is headed by a row of its own.
 *
 * The screen builds this once per list and hands it to everything that must agree about grid
 * positions -- the grid, the scrubber, the pill, the arrow keys and the list the viewer pages
 * through -- so none of them can draw or count a different order (TRAPS #5 and #9).
 */
internal class ZoomLayout(val groups: List<TimelineGroup>, val hasHeaders: Boolean) {
    /** Every photo in the order the grid draws it; the list a tap hands to the viewer. */
    val renderedAssets: List<MediaAsset> =
        if (groups.size == 1) groups[0].assets else groups.flatMap { it.assets }
}

/** The layout for [assets]: month runs under headers when [headers] is set, else one plain group. */
internal fun zoomLayoutFor(
    assets: List<MediaAsset>,
    headers: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): ZoomLayout = if (headers) {
    ZoomLayout(timelineRuns(assets, TimelineGrouping.MONTH, zoneId, locale), hasHeaders = true)
} else {
    ZoomLayout(listOf(TimelineGroup("all", "", assets)), hasHeaders = false)
}
