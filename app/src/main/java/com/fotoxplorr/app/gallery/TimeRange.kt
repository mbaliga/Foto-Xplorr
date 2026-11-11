package com.fotoxplorr.app.gallery

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.fotoxplorr.app.media.MediaAsset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A span of time the browser is narrowed to: the grid and the map both show only what falls
 * inside it ("time acts as a filter", owner #27).
 *
 * Half-open, `[fromMillis, untilMillis)`, and month-granular by construction: [ofMonths] snaps
 * to the start of a month at each end, so "Mar 2023 - Sep 2024" is exactly the instants from 1
 * March 2023 up to, but not including, 1 October 2024 in the zone it was made in. A range is a
 * lens applied AFTER a view's projection (see [inTimeRange]); nothing about what a destination,
 * route or search selects changes, which is also why the projection fingerprints and the
 * catalogue goldens never see it.
 */
data class TimeRange(val fromMillis: Long, val untilMillis: Long) {
    operator fun contains(millis: Long): Boolean = millis >= fromMillis && millis < untilMillis

    companion object {
        /**
         * The range from the start of [from] to the end of [to], both inclusive. Given the other
         * way round, they are swapped rather than refused: a slider's two handles can cross.
         */
        fun ofMonths(from: YearMonth, to: YearMonth, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val first = minOf(from, to)
            val last = maxOf(from, to)
            return TimeRange(monthStartMillis(first, zone), monthStartMillis(last.plusMonths(1), zone))
        }
    }
}

/** A month's position on a single line of months, so a slider can address it as one number. */
internal val YearMonth.index: Int get() = year * 12 + month

/** The inverse of [index]. */
internal fun yearMonthOfIndex(index: Int): YearMonth = YearMonth(index.floorDiv(12), index.mod(12))

internal fun YearMonth.plusMonths(months: Int): YearMonth = yearMonthOfIndex(index + months)

/** The first instant of [month] in [zone]. ([YearMonth.month] is zero-based, as Calendar's is.) */
internal fun monthStartMillis(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.of(month.year, month.month + 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

/** The month [millis] falls in, in [zone]. */
internal fun yearMonthAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): YearMonth {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    return YearMonth(date.year, date.monthValue - 1)
}

/**
 * [this] narrowed to [range], or the very same list when there is none (so a view with no range
 * costs nothing and keeps its identity for every `remember` keyed on it).
 *
 * Judged on [timelineMillis] -- the date taken, else the file's modified time -- the same field
 * the date headers, the edge scrubber and the pill read, so a photo is "in March" for all of them
 * or for none. The search language's own date window reads the date taken only; both are applied,
 * so a typed `august 2025` and a range intersect.
 */
internal fun List<MediaAsset>.inTimeRange(range: TimeRange?): List<MediaAsset> =
    if (range == null) this else filter { it.timelineMillis in range }

/**
 * The first and last month that hold anything in [assets], in one pass: the domain a month slider
 * spans. Items with no usable date at all (a timeline millis of zero) are left out -- they can
 * never be inside a range, and a slider reaching back to 1970 for them would be useless.
 * Null when nothing is dated.
 */
internal fun monthExtent(
    assets: List<MediaAsset>,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<YearMonth, YearMonth>? {
    var lowest = Long.MAX_VALUE
    var highest = Long.MIN_VALUE
    for (asset in assets) {
        val millis = asset.timelineMillis
        if (millis <= 0L) continue
        if (millis < lowest) lowest = millis
        if (millis > highest) highest = millis
    }
    if (highest == Long.MIN_VALUE) return null
    return yearMonthAt(lowest, zone) to yearMonthAt(highest, zone)
}

/**
 * How the range reads on the chip: `Mar 2023 – Sep 2024`, `Mar 2023` for one month, `2024` for
 * January to December of one year and `2023 – 2024` for whole years.
 */
internal fun TimeRange.label(
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val first = yearMonthAt(fromMillis, zone)
    val last = yearMonthAt(maxOf(fromMillis, untilMillis - 1L), zone)
    val monthName = DateTimeFormatter.ofPattern("MMM", locale)
    fun short(month: YearMonth) =
        "${monthName.format(LocalDate.of(month.year, month.month + 1, 1))} ${month.year}"
    val wholeYears = first.month == 0 && last.month == 11
    return when {
        first == last -> short(first)
        wholeYears && first.year == last.year -> first.year.toString()
        wholeYears -> "${first.year} – ${last.year}"
        else -> "${short(first)} – ${short(last)}"
    }
}

/**
 * The [Saver] that carries a range (or the absence of one) through process death. An empty list
 * is "no range"; anything that is not a forward pair of longs restores as no range, never as a
 * crash reading its own saved state.
 */
internal val TimeRangeSaver: Saver<TimeRange?, Any> = listSaver<TimeRange?, Long>(
    save = { range -> if (range == null) emptyList() else listOf(range.fromMillis, range.untilMillis) },
    restore = { saved ->
        if (saved.size == 2 && saved[1] > saved[0]) TimeRange(saved[0], saved[1]) else null
    },
)
