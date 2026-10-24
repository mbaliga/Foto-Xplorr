@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The chip that says a time range is narrowing what is shown, with how much is left, and the
 * button that takes it off.
 *
 * Always visible while a range is set, on the grid and on the map alike, because a filter that can
 * silently make a destination look empty has to be impossible to forget about. The count is what
 * makes a range that matches nothing explain itself -- `Mar 2023 · 0` -- with no change to any
 * empty-state copy. The body opens the editor; the clear button is [CLEAR_TARGET_DP] square.
 */
@Composable
internal fun TimeRangeChip(
    range: TimeRange,
    shownCount: Int,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = range.label()
    Row(
        modifier = modifier
            .heightIn(min = CLEAR_TARGET_DP.dp)
            .background(PANEL, RoundedCornerShape(CLEAR_TARGET_DP.dp / 2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label · $shownCount",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = CLEAR_TARGET_DP.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Time range $label, $shownCount shown. Tap to change."
                }
                .clickable(role = Role.Button, onClick = onEdit)
                .padding(start = 18.dp, end = 4.dp)
                .padding(vertical = 13.dp),
        )
        Box(
            Modifier
                .size(CLEAR_TARGET_DP.dp)
                .semantics { contentDescription = "Clear time range" }
                .clickable(role = Role.Button, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The panel that sets the range: a row of year chips (a tap is that whole year) and, beneath, a
 * slider over the months [domain] spans with a handle at each end.
 *
 * The slider only DRAGS locally. The range is applied when a handle is let go, not on every tick,
 * because a range change re-derives the whole projection and re-draws the map; doing that under a
 * moving finger is the stutter this avoids. Releasing with both handles at the ends of [domain]
 * means "all of it" and clears the range rather than setting one that filters nothing.
 *
 * @param domain the first and last month the current view has anything in; null when nothing in it
 *   is dated.
 * @param range the range now applied, which positions the handles; null is the whole of [domain].
 */
@Composable
internal fun TimeRangeEditor(
    domain: Pair<YearMonth, YearMonth>?,
    range: TimeRange?,
    onApply: (TimeRange) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = EDITOR_MAX_WIDTH_DP.dp)
            .background(PANEL, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Time",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (range != null) {
                Text(
                    "All time",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .heightIn(min = CLEAR_TARGET_DP.dp)
                        .clickable(role = Role.Button, onClick = onClear)
                        .padding(horizontal = 12.dp)
                        .padding(vertical = 13.dp),
                )
            }
            Box(
                Modifier
                    .size(CLEAR_TARGET_DP.dp)
                    .semantics { contentDescription = "Close time filter" }
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (domain == null) {
            Text(
                "Nothing in this view has a date to filter on.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            return@Column
        }

        val firstIndex = domain.first.index
        val lastIndex = domain.second.index

        // Newest year first: the grid opens on the newest photos, so the chips start there too.
        val years = remember(firstIndex, lastIndex) {
            (domain.second.year downTo domain.first.year).toList()
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years, key = { it }) { year ->
                val whole = TimeRange.ofMonths(YearMonth(year, 0), YearMonth(year, 11))
                YearChip(
                    year = year,
                    selected = range == whole,
                    onClick = { onApply(whole) },
                )
            }
        }

        if (firstIndex == lastIndex) {
            // One month holds everything: a slider over a single stop would have nothing to move.
            Text(
                TimeRange.ofMonths(domain.first, domain.first).label(),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            return@Column
        }

        // Where the handles sit while dragging, in month indices. Re-seeded whenever the applied
        // range or the domain changes under it (a year chip, a clear, another destination).
        var handles by remember(range, firstIndex, lastIndex) {
            mutableStateOf(handlesFor(range, firstIndex, lastIndex))
        }
        val from = handles.start.roundToInt().coerceIn(firstIndex, lastIndex)
        val to = handles.endInclusive.roundToInt().coerceIn(from, lastIndex)
        RangeSlider(
            value = handles,
            onValueChange = { handles = it },
            valueRange = firstIndex.toFloat()..lastIndex.toFloat(),
            // Rounded on release rather than stepped: a step per month would draw a tick per
            // month, a hundred and more across a decade.
            steps = 0,
            onValueChangeFinished = {
                if (from == firstIndex && to == lastIndex) {
                    onClear()
                } else {
                    onApply(TimeRange.ofMonths(yearMonthOfIndex(from), yearMonthOfIndex(to)))
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            TimeRange.ofMonths(yearMonthOfIndex(from), yearMonthOfIndex(to)).label(),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun YearChip(year: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = CLEAR_TARGET_DP.dp)
            .semantics { contentDescription = "Show $year" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            year.toString(),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Where the slider's two handles start for [range]: its months, clamped into the domain, or all of it. */
internal fun handlesFor(range: TimeRange?, firstIndex: Int, lastIndex: Int): ClosedFloatingPointRange<Float> {
    if (range == null) return firstIndex.toFloat()..lastIndex.toFloat()
    val from = yearMonthAt(range.fromMillis).index.coerceIn(firstIndex, lastIndex)
    val to = yearMonthAt(maxOf(range.fromMillis, range.untilMillis - 1L)).index.coerceIn(from, lastIndex)
    return from.toFloat()..to.toFloat()
}

// Fixed on purpose (reference design): the same near-black the pill and the discs are, not a theme
// surface -- it floats over photographs, which are the same colours in either theme.
private val PANEL = Color.Black.copy(alpha = 0.88f)

/** The smallest touch target the chip's and the editor's buttons are given. */
internal const val CLEAR_TARGET_DP = 48

private const val EDITOR_MAX_WIDTH_DP = 480
