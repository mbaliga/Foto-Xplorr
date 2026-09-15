package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.FotoStamp
import java.util.Calendar

/**
 * The library as a month calendar, one stamp per day.
 *
 * The owner's reference is a light, soft-shadowed calendar; the direction that came with it was
 * explicit that it is *"just a Layout reference, we will always be brutalist"*. So the layout is
 * taken -- month heading, weekday rule, a stamp-framed thumbnail on each day that has photos,
 * greyed-out days from the neighbouring months -- and the styling is not. Black ground, no
 * shadows, no rounded cards.
 *
 * The stamp is [FotoStamp], the same silhouette the share frame and the map pins use, so a day
 * tile here and a shared photo are visibly the same family.
 */
@Composable
fun CalendarScreen(
    assets: List<MediaAsset>,
    onOpenDay: (List<MediaAsset>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bucket once per asset list, not per recomposition: this walks the whole library.
    val byMonth = remember(assets) { groupByMonth(assets) }
    val months = remember(byMonth) { byMonth.keys.sortedDescending() }
    var monthIndex by remember(months) { mutableStateOf(0) }

    if (months.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No dated photos yet", color = Color.White.copy(alpha = 0.6f))
        }
        return
    }

    val month = months[monthIndex.coerceIn(0, months.lastIndex)]
    val daysWithPhotos = byMonth[month].orEmpty()

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "%02d".format(month.month + 1),
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    text = "${monthName(month.month)} ${month.year}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Newer months are at index 0, so "previous" walks the index UP. Named by what the
            // user is doing, not by which way the index moves.
            NavArrow("‹", enabled = monthIndex < months.lastIndex) { monthIndex += 1 }
            NavArrow("›", enabled = monthIndex > 0) { monthIndex -= 1 }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            WEEKDAYS.forEach { day ->
                Text(
                    text = day,
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        val cells = remember(month, daysWithPhotos) { monthCells(month, daysWithPhotos) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(cells, key = { it.key }) { cell ->
                DayCell(cell = cell, onOpen = { onOpenDay(cell.assets) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(bottom = 88.dp))
            }
        }
    }
}

@Composable
private fun NavArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        color = Color.White.copy(alpha = if (enabled) 0.9f else 0.25f),
        fontSize = 28.sp,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun DayCell(cell: DayCellData, onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = cell.dayOfMonth.toString(),
            // A day outside this month is present for the grid's shape only -- it keeps the
            // weekday columns honest -- so it is dimmed rather than omitted.
            color = if (cell.inMonth) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
            style = MaterialTheme.typography.labelMedium,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cover = cell.assets.firstOrNull()
            if (cover != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // The stamp is a real clip, so the photo itself is stamp-shaped against
                        // the black rather than having notches painted over it.
                        .clip(FotoStamp)
                        .background(Color(0xFF101010))
                        .clickable(onClick = onOpen),
                ) {
                    MediaImage(
                        asset = cover,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

// ---------------- pure date bucketing, kept testable ----------------

/** Year and zero-based month, ordered so a plain sort is chronological. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    override fun compareTo(other: YearMonth): Int =
        if (year != other.year) year.compareTo(other.year) else month.compareTo(other.month)
}

/** One square in the month grid. */
data class DayCellData(
    val key: String,
    val dayOfMonth: Int,
    val inMonth: Boolean,
    val assets: List<MediaAsset>,
)

/**
 * Bucket assets by the month they were taken in, and by day within it.
 *
 * Uses `dateTakenMillis` when present and falls back to the file's modified time, because a photo
 * saved from a messaging app frequently has no capture date at all -- and a calendar that silently
 * dropped every such photo would look broken to anyone whose library is mostly saved images.
 */
internal fun groupByMonth(assets: List<MediaAsset>): Map<YearMonth, Map<Int, List<MediaAsset>>> {
    val calendar = Calendar.getInstance()
    val result = linkedMapOf<YearMonth, MutableMap<Int, MutableList<MediaAsset>>>()
    assets.forEach { asset ->
        if (asset.isTrashed) return@forEach
        val millis = asset.dateTakenMillis.takeIf { it > 0L }
            ?: (asset.dateModifiedSeconds * 1000L).takeIf { it > 0L }
            ?: return@forEach
        calendar.timeInMillis = millis
        val ym = YearMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH))
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        result.getOrPut(ym) { linkedMapOf() }.getOrPut(day) { mutableListOf() }.add(asset)
    }
    return result
}

/**
 * The 7-column grid for one month, including the leading and trailing days of the neighbouring
 * months that share its first and last weeks.
 *
 * Those neighbours are what keep the weekday columns aligned; without them the 1st of a month
 * would always land in the first column and the whole grid would lie about which day is which.
 */
internal fun monthCells(month: YearMonth, byDay: Map<Int, List<MediaAsset>>): List<DayCellData> {
    val calendar = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, month.year)
        set(Calendar.MONTH, month.month)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar.SUNDAY is 1, and the grid's first column is Sunday, so this is the count of blanks
    // before the 1st.
    val leading = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY

    val previousMonth = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, month.year)
        set(Calendar.MONTH, month.month)
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, -1)
    }
    val daysInPrevious = previousMonth.getActualMaximum(Calendar.DAY_OF_MONTH)

    val cells = mutableListOf<DayCellData>()
    for (i in 0 until leading) {
        val day = daysInPrevious - leading + 1 + i
        cells += DayCellData("prev-$day", day, inMonth = false, assets = emptyList())
    }
    for (day in 1..daysInMonth) {
        cells += DayCellData("day-$day", day, inMonth = true, assets = byDay[day].orEmpty())
    }
    // Fill out the final week so the grid ends on a clean row rather than a ragged one.
    while (cells.size % 7 != 0) {
        val day = cells.size % 7
        cells += DayCellData("next-${cells.size}", day, inMonth = false, assets = emptyList())
    }
    return cells
}

private fun monthName(zeroBasedMonth: Int): String = MONTHS.getOrElse(zeroBasedMonth) { "" }

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
