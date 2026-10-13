package com.fotoxplorr.app.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk
import dev.aarso.cellshell.SpatialMotion
import dev.aarso.cellshell.WheelItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The destinations as the owner's swivel reference (2026-10) draws them: large bold words down a
 * black ground, the current one pure white with a filled square to its left, every other word
 * dimmed by how many rows it is from the current one.
 *
 * An app-local wheel, not `dev.aarso:cell-shell`'s `WordWheelRail`. The shared rail's type (34sp
 * on a 56dp pitch, bold only on the focus line), its fade (0.62 − 0.1·d, floor 0.22) and its
 * marker box are private constants a host cannot reach, and none of them is the reference's —
 * which sets 44sp bold on an 80dp pitch and fades through #9A9A9A, #707070, #4A4A4A, #2E2E2E.
 * Restyling through parameters was not possible, so this is the same wheel re-cut; the shared
 * library is untouched.
 *
 * The wheel's BEHAVIOUR is kept, because the owner's reference video set it and a still cannot
 * unset it: the run turns under the finger; the fade is read off the fractional scroll position
 * so it moves continuously rather than re-highlighting on settle; release snaps to the nearest row
 * and selects it; and the marker is pinned to its row, not to the focus line, so a selection
 * change makes it travel back across the intervening rows — a little slower than the words, and
 * stretching along its path as it goes — rather than teleport.
 *
 * **Nothing per-frame recomposes.** Each row's alpha is read in its graphics layer, the marker's
 * position in its placement lambda, and the weight is constant (every word is bold), so a turn of
 * the wheel is draw-phase work only — the shared rail re-lays-out text as weight steps.
 *
 * @param items the destinations, top to bottom.
 * @param selectedId the item at the focus line. The source of truth: the rail follows it and
 *   holds no selection of its own.
 * @param onSelect fired when a row is tapped, and when a turn settles on a new row.
 */
@Composable
fun DestinationWordRail(
    items: List<WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current

        // The pitch is in sp, not dp, so the wheel grows with the user's font scale instead of
        // clipping 44sp type into an 80dp box. Every row is pinned to exactly this height, which
        // is what lets the focus-line geometry be arithmetic on the scroll offset.
        val pitchDp = with(density) { RAIL_ROW_PITCH_SP.sp.toDp() }
        val pitchPx = with(density) { pitchDp.toPx() }

        // Half a viewport of blank above and below, so the first and last words can reach the
        // focus line like any other. Unbounded height has no centre; fall back to one row.
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else pitchPx
        val padPx = ((viewportPx - pitchPx) / 2f).coerceAtLeast(0f)
        val padDp = with(density) { padPx.toDp() }
        val focusLinePx = padPx + pitchPx / 2f

        // With that padding, one pitch of scroll moves the focus line one row, so this IS the
        // wheel's position in row units, fractional part and all.
        fun scrollRows(): Float = scroll.value / pitchPx

        // The marker's row. It trails [selectedIndex] rather than tracking the focus line: while
        // the wheel turns, selection has not changed yet, so the marker rides away with its row.
        val markerRow = remember { Animatable(selectedIndex.toFloat()) }
        var travelFrom by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
        var travelTo by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex, pitchPx) {
            val target = (selectedIndex * pitchPx).roundToInt()
            if (!placed) {
                // First composition: the selected room is simply already there.
                placed = true
                scroll.scrollTo(target)
                markerRow.snapTo(selectedIndex.toFloat())
                travelFrom = selectedIndex.toFloat()
                travelTo = selectedIndex.toFloat()
            } else {
                travelFrom = markerRow.value
                travelTo = selectedIndex.toFloat()
                // The words settle on the shell's contract spec; the marker uses a longer run so
                // it visibly lags them and catches up at the focus line.
                launch { scroll.animateScrollTo(target, SpatialMotion.settleSpec) }
                markerRow.animateTo(
                    selectedIndex.toFloat(),
                    tween(MARKER_TRAVEL_MS, easing = FastOutSlowInEasing),
                )
            }
        }

        // A wheel does not stop between detents: on release, snap to the nearest row and report
        // it — turning the wheel IS selecting.
        val currentSelectedId by rememberUpdatedState(selectedId)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentItems by rememberUpdatedState(items)
        LaunchedEffect(scroll, pitchPx) {
            var wasScrolling = false
            snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
                // Only a true→false edge is a release; the initial false must not fire onSelect.
                if (wasScrolling && !scrolling) {
                    val landing = currentItems
                    val nearest = (scroll.value / pitchPx).roundToInt().coerceIn(0, landing.lastIndex)
                    if (landing[nearest].id != currentSelectedId) currentOnSelect(landing[nearest].id)
                    // A programmatic settle is itself a scroll and comes back through this edge;
                    // without the guard the wheel would animate to where it already is, forever.
                    val detent = (nearest * pitchPx).roundToInt()
                    if (scroll.value != detent) scroll.animateScrollTo(detent, SpatialMotion.settleSpec)
                }
                wasScrolling = scrolling
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.height(padDp))
            items.forEachIndexed { index, item ->
                DestinationRow(
                    item = item,
                    isSelected = item.id == selectedId,
                    rowHeight = pitchDp,
                    // Deferred: read on the draw pass, never captured here, so a turn of the
                    // wheel does not recompose this loop.
                    distance = { index - scrollRows() },
                    onClick = { if (item.id != selectedId) onSelect(item.id) },
                )
            }
            Spacer(Modifier.height(padDp))
        }

        // The marker: the reference's filled square, riding the gutter beside the selected row
        // and only that row. Position, alpha and the travel stretch are all read inside deferred
        // lambdas, so it tracks a turn frame by frame without recomposing.
        val markerHalfPx = with(density) { RAIL_MARKER_DP.dp.toPx() / 2f }
        Box(
            modifier = Modifier
                .size(RAIL_MARKER_DP.dp)
                .offset {
                    IntOffset(
                        0,
                        (focusLinePx + (markerRow.value - scrollRows()) * pitchPx - markerHalfPx)
                            .roundToInt(),
                    )
                }
                .graphicsLayer {
                    alpha = destinationRowFade(markerRow.value - scrollRows())
                    // Motion carries the state change: the square lengthens along its path,
                    // most at mid-flight and not at all at either end, so it reads as moving
                    // rather than as having been redrawn somewhere else.
                    val stretch = markerStretch(markerRow.value, travelFrom, travelTo)
                    scaleY = 1f + MARKER_STRETCH * stretch
                    scaleX = 1f - MARKER_SQUEEZE * stretch
                }
                .background(Color.White),
        )
    }
}

/**
 * One word in the run. Its own composable so it has its own recomposition scope — though with a
 * constant weight, a turn of the wheel never invalidates it at all.
 */
@Composable
private fun DestinationRow(
    item: WheelItem,
    isSelected: Boolean,
    rowHeight: Dp,
    distance: () -> Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            // Alpha in a graphics layer: the lambda re-runs on the draw pass when the scroll
            // offset changes, so the fade is continuous under the finger for free.
            .graphicsLayer { alpha = destinationRowFade(distance()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: the rail is flat type on a flat ground, and touch chrome would be
                // the only box on the surface.
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed gutter so every word sits on one optical left edge whether or not the marker
        // is beside it — the words must not shuffle sideways when selection moves.
        Spacer(Modifier.width(RAIL_GUTTER_DP.dp))
        BasicText(
            text = item.label,
            style = TextStyle(
                color = Color.White,
                fontFamily = HyleGrotesk,
                fontSize = RAIL_FONT_SP.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = RAIL_LETTER_SPACING_SP.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The reference's word greys, by whole rows from the selected one, as alphas of white on black:
 * the current word is pure white, then #9A9A9A, #707070, #4A4A4A and #2E2E2E. Four rows out and
 * beyond sit on that last value — a floor, not a fade to nothing, because a word that vanishes
 * has left the rail, and the ends of a nine-word run have to stay present.
 *
 * Integer and pure so the curve can be asserted rather than eyeballed; [destinationRowFade] is
 * the fractional reading the rail actually draws.
 */
internal fun destinationRowAlpha(distance: Int): Float = when (abs(distance)) {
    0 -> 1f
    1 -> 0x9A / 255f
    2 -> 0x70 / 255f
    3 -> 0x4A / 255f
    else -> 0x2E / 255f
}

/**
 * [destinationRowAlpha] read at a fractional [distance] from the focus line.
 *
 * Mid-drag a row sits 1.4 rows out, and quantising that to 1 makes the fade change in steps on
 * settle — the list-like re-highlight the wheel exists to avoid. So the integer curve is sampled
 * at the two bracketing rows and interpolated, which reproduces it exactly at every whole row and
 * is smooth everywhere between.
 */
internal fun destinationRowFade(distance: Float): Float {
    val d = abs(distance)
    val low = floor(d).toInt()
    val fraction = d - low
    val nearer = destinationRowAlpha(low)
    val further = destinationRowAlpha(low + 1)
    return nearer + (further - nearer) * fraction
}

/**
 * How far through its journey the marker is, as 0 at rest at either end and 1 at the midpoint.
 * A parabola rather than "distance remaining" so the stretch is symmetric: it has to settle back
 * to a square on arrival, not merely shrink towards one.
 */
internal fun markerStretch(current: Float, from: Float, to: Float): Float {
    val span = to - from
    if (abs(span) < 0.001f) return 0f
    val progress = ((current - from) / span).coerceIn(0f, 1f)
    return 4f * progress * (1f - progress)
}

/** The reference's type: 44sp bold on an 80dp pitch, tracked a touch tight as display type is. */
internal const val RAIL_FONT_SP = 44
internal const val RAIL_ROW_PITCH_SP = 80
private const val RAIL_LETTER_SPACING_SP = -0.5f

/** The reference's square beside the current word, and the gutter it rides so words sit at 48. */
internal const val RAIL_MARKER_DP = 16
internal const val RAIL_GUTTER_DP = 32

/** Longer than [SpatialMotion.settleSpec]'s 320ms so the marker's lag behind the words is seen. */
private const val MARKER_TRAVEL_MS = 420

/** How much the square lengthens along its path at mid-flight, and narrows across it. */
private const val MARKER_STRETCH = 0.4f
private const val MARKER_SQUEEZE = 0.12f
