package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import dev.aarso.cellshell.ScrubberStop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The labelled stops for the edge scrubber over a grid of media.
 *
 * The scrubber is a map of the list, so its stops have to be derived from the list *as sorted*,
 * not from a calendar. Walking the assets in their displayed order and cutting a stop wherever
 * the month changes gives that for free: it stays correct when the sort is oldest-first, and it
 * degenerates gracefully when the sort is by name or size, where consecutive items are in no
 * date order at all and the run of stops simply becomes dense.
 *
 * Labels carry the year only when it changes. A column reading `Mar / Feb / Jan / 2023 / Dec`
 * says where the year boundary is without repeating four digits down the whole strip, which at
 * the 9sp the strip draws at is the difference between a legible timeline and a grey smear.
 *
 * Index space is the **grid's**, which for every surface that shows this scrubber is the same as
 * the asset list's: the root destination and the drill-down routes both render headerless grids
 * (`showDateHeaders = false`), so item *n* of the grid is asset *n*. A grid that drew date
 * headers would insert extra items and shift everything below each one — hence
 * [timelineStops] taking the exact list the grid was handed, and nothing else.
 */
fun timelineStops(
    assets: List<MediaAsset>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ScrubberStop> {
    if (assets.isEmpty()) return emptyList()
    val monthFormat = DateTimeFormatter.ofPattern("MMM", locale).withZone(zoneId)
    val stops = ArrayList<ScrubberStop>()
    var lastKey: String? = null
    var lastYear: Int? = null
    assets.forEachIndexed { index, asset ->
        val instant = Instant.ofEpochMilli(asset.timelineMillis)
        val date = instant.atZone(zoneId)
        val key = "${date.year}-${date.monthValue}"
        if (key == lastKey) return@forEachIndexed
        val label = if (date.year != lastYear) {
            // The year gets its own line rather than being appended to the month: "Mar 2024" is
            // too wide for the strip and would ellipsize into "Mar…", which reads as broken.
            if (lastYear == null) monthFormat.format(instant) else date.year.toString()
        } else {
            monthFormat.format(instant)
        }
        stops += ScrubberStop(label, index)
        lastKey = key
        lastYear = date.year
    }
    return stops
}

/** The month-and-year an asset sits in, spelled out — for readouts that have room for it. */
fun monthLabel(
    asset: MediaAsset,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(asset.timelineMillis))

/**
 * The moment an asset sits at on the timeline.
 *
 * `dateTakenMillis` is the truth when it exists, but MediaStore leaves it at 0 for anything
 * without the metadata — screenshots, downloads, files copied from a computer, which on a real
 * device is a large share of the library. Falling back to the file's modification time keeps
 * those items somewhere sensible instead of piling every one of them onto 1 January 1970, which
 * would put a single enormous stop at one end of the strip and squash the actual timeline into
 * nothing.
 */
internal val MediaAsset.timelineMillis: Long
    get() = if (dateTakenMillis > 0L) dateTakenMillis else dateModifiedSeconds * 1000L
