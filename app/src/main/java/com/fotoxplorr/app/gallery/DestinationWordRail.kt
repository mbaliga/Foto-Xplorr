package com.fotoxplorr.app.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
 * unset it: the run turns under the finger and the fade is read off the fractional scroll
 * position so it moves continuously rather than re-highlighting on settle.
 *
 * What changed (owner, 2026-10, *"release should not take you to a location -- if scroll and
 * release is the interaction, then the selection indicator must move as well, and the change
 * should reflect on the pane"*): the square tracks the focus line LIVE, hopping from word to word
 * as the wheel turns, and the hovered destination is committed once the wheel hesitates
 * ([RAIL_COMMIT_DEBOUNCE_MS]) -- while the finger is still down -- so the pane follows the
 * indicator. Lifting the finger only SETTLES the wheel on its nearest word; it never navigates and
 * never closes the room (that is [onReselect]'s job).
 *
 * **Nothing per-frame recomposes.** Each row's alpha is read in its graphics layer, the marker's
 * position in its placement lambda, and the weight is constant (every word is bold), so a turn of
 * the wheel is draw-phase work only — the shared rail re-lays-out text as weight steps.
 *
 * @param items the destinations, top to bottom.
 * @param selectedId the item at the focus line. The source of truth: the rail follows it and
 *   holds no selection of its own.
 * @param onSelect fired when a row is tapped, when a turn hesitates on a new row, and when a turn
 *   ends on one that has not been committed yet.
 * @param onReselect fired when the ALREADY-selected row is tapped. Release no longer closes the
 *   room, so tapping the current word is how the user says "this one, close the menu". It is also
 *   how a pane (Settings, a destination screen) is left from the word you came from: that word is
 *   still the selected one, so it is never an `onSelect`.
 * @param paneOpen whether a pane is open over the destination [selectedId] names. Then a scrub or
 *   a fling that comes to rest on the selected word is a choice of it, not a no-op, and says so
 *   through [onLeavePane] (and not [onReselect], which also closes the room under the finger).
 * @param onLeavePane fired when the wheel rests on the selected word while [paneOpen].
 */
@Composable
fun DestinationWordRail(
    items: List<WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReselect: () -> Unit = {},
    paneOpen: Boolean = false,
    onLeavePane: () -> Unit = {},
) {
    if (items.isEmpty()) return

    // -1 when the id is unknown: nothing is selected, so any hovered row is a change.
    val selectedRaw = items.indexOfFirst { it.id == selectedId }
    val selectedIndex = selectedRaw.coerceAtLeast(0)
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

        // The marker's row: the word under the focus line, so the square is always beside the
        // bright word and hops with the wheel. It is an animated row index rather than the focus
        // index itself so the hop is a short glide, not a teleport.
        val markerRow = remember { Animatable(selectedIndex.toFloat()) }
        var travelFrom by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
        var travelTo by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

        val currentSelectedId by rememberUpdatedState(selectedId)
        val currentSelectedRaw by rememberUpdatedState(selectedRaw)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentPaneOpen by rememberUpdatedState(paneOpen)
        val currentOnLeavePane by rememberUpdatedState(onLeavePane)
        val currentItems by rememberUpdatedState(items)

        var placed by remember { mutableStateOf(false) }
        // True while the rail is moving its OWN wheel (placement, following a tap, settling on a
        // detent). Those scrolls pass through rows the user never chose, so they are never a
        // hover worth committing and their end is never a release.
        var settling by remember { mutableStateOf(false) }
        suspend fun moveWheelTo(px: Int, animated: Boolean) {
            settling = true
            try {
                if (animated) scroll.animateScrollTo(px, SpatialMotion.settleSpec) else scroll.scrollTo(px)
            } finally {
                settling = false
            }
        }

        // Whether a finger is on the wheel and moving it, and whether the wheel has been turned by
        // one since it last landed. The scroll state's own `isScrollInProgress` cannot say: it is
        // equally true for the rail's animations, whose interruption (a second tap, a finger
        // grabbing the wheel) would otherwise read as a release. A turn with no finger -- a mouse
        // wheel, a trackpad, a screen reader's scroll action -- has no drag to see; it is told
        // apart by starting while the rail is not moving the wheel itself (see below).
        var dragging by remember { mutableStateOf(false) }
        var turnedByUser by remember { mutableStateOf(false) }
        LaunchedEffect(scroll) {
            scroll.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        dragging = true
                        turnedByUser = true
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                    else -> Unit
                }
            }
        }

        LaunchedEffect(selectedIndex, pitchPx) {
            val target = (selectedIndex * pitchPx).roundToInt()
            if (!placed) {
                // First composition: the selected room is simply already there.
                moveWheelTo(target, animated = false)
                placed = true
            } else if (
                // A commit the wheel itself caused (the finger or a fling is still moving it, and
                // it is already on this row): animating now would fight the finger. The release
                // below settles it on the detent.
                !(scroll.isScrollInProgress && railFocusIndex(scroll.value, pitchPx, currentItems.lastIndex) == selectedIndex) &&
                scroll.value != target
            ) {
                // An outside change (a tap, a selection set elsewhere): the words settle on the
                // shell's contract spec.
                launch { moveWheelTo(target, animated = true) }
            }
        }

        // The marker follows the focus line. Until the first placement the wheel sits at 0, which
        // is not where the user is, so the selected row stands in.
        LaunchedEffect(scroll, pitchPx) {
            snapshotFlow {
                if (placed) railFocusIndex(scroll.value, pitchPx, currentItems.lastIndex) else selectedIndex
            }.collectLatest { row ->
                travelFrom = markerRow.value
                travelTo = row.toFloat()
                markerRow.animateTo(row.toFloat(), tween(MARKER_HOP_MS, easing = FastOutSlowInEasing))
            }
        }

        // Commit while scrubbing: once the wheel has rested on a new word for the debounce, that
        // word becomes the selection, so the pane follows the indicator without recomputing for
        // every row of a fast fling. collectLatest drops a pending commit the moment the focus
        // moves on or returns to the selected word.
        LaunchedEffect(scroll, pitchPx) {
            snapshotFlow {
                railPendingCommit(
                    focusIndex = railFocusIndex(scroll.value, pitchPx, currentItems.lastIndex),
                    selectedIndex = currentSelectedRaw,
                    dragging = dragging && scroll.isScrollInProgress && !settling,
                    paneOpen = currentPaneOpen,
                )
            }.collectLatest { hovered ->
                if (hovered != null) {
                    delay(RAIL_COMMIT_DEBOUNCE_MS)
                    if (hovered == currentSelectedRaw) {
                        // Only reachable with a pane open: the scrub has come back to the word
                        // the pane sits over, which is choosing it.
                        currentOnLeavePane()
                    } else {
                        currentItems.getOrNull(hovered)?.let { currentOnSelect(it.id) }
                    }
                }
            }
        }

        // A wheel does not stop between detents. When a turn the user made comes to rest -- the
        // finger lifted, or its fling ran out -- settle on the nearest row. This is ONLY a settle:
        // the hovered row has normally been committed already, and a turn that never paused gets
        // its one commit here, but nothing closes the room and nothing navigates anywhere the
        // indicator was not already pointing.
        LaunchedEffect(scroll, pitchPx) {
            var wasScrolling = false
            snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
                // A scroll that begins while the rail is not moving its own wheel is a user's whether
                // or not a finger drags it (mouse wheel, trackpad, TalkBack): without this it
                // neither committed nor settled, and left the wheel between words.
                if (scrolling && !wasScrolling && !settling) turnedByUser = true
                // Only a true->false edge is a landing; the initial false must not fire onSelect.
                if (wasScrolling && !scrolling && turnedByUser) {
                    turnedByUser = false
                    val landing = currentItems
                    val nearest = railFocusIndex(scroll.value, pitchPx, landing.lastIndex)
                    when (railLanding(nearest, currentSelectedRaw, currentPaneOpen)) {
                        RailLanding.SELECT -> currentOnSelect(landing[nearest].id)
                        RailLanding.LEAVE_PANE -> currentOnLeavePane()
                        RailLanding.NONE -> Unit
                    }
                    // The settle is itself a scroll and comes back through this edge, but it is not
                    // a user turn, so it ends here. A child job, so a finger that grabs the wheel
                    // mid-settle cancels the settle alone rather than this collector.
                    val detent = (nearest * pitchPx).roundToInt()
                    if (scroll.value != detent) launch { moveWheelTo(detent, animated = true) }
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
                    onClick = { if (item.id != selectedId) onSelect(item.id) else onReselect() },
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
                // After the offset, so the semantics bounds are where the square is drawn.
                .testTag(RAIL_MARKER_TAG)
                .graphicsLayer {
                    alpha = destinationRowFade(markerRow.value - scrollRows())
                    // Motion carries the state change: the square lengthens along its path,
                    // most at mid-flight and not at all at either end, so it reads as moving
                    // rather than as having been redrawn somewhere else.
                    val stretch = markerStretch(markerRow.value, travelFrom, travelTo)
                    scaleY = 1f + MARKER_STRETCH * stretch
                    scaleX = 1f - MARKER_SQUEEZE * stretch
                }
                // Fixed on purpose (reference design): the white marker and distance-fade greys are
                // transcribed from the rail reference.
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
 * The row under the focus line for a scroll offset: with the wheel's half-viewport padding one
 * [pitchPx] of scroll moves the focus line exactly one row, so this is the offset in rows, rounded
 * to the nearest. The single definition for the marker's target, the debounced commit and the
 * release snap, so they can never disagree about which word is "the one under the finger".
 */
internal fun railFocusIndex(scrollPx: Int, pitchPx: Float, lastIndex: Int): Int {
    if (lastIndex <= 0 || pitchPx <= 0f) return 0
    return (scrollPx / pitchPx).roundToInt().coerceIn(0, lastIndex)
}

/**
 * Which row the debounce should be waiting to commit, or null for none. Only a finger actually
 * dragging the wheel (not its fling after the lift, which commits once where it lands, and not the
 * rail's own settle or follow-the-selection animation, which pass through rows nobody chose) that
 * is resting on a row other than the selected one has anything pending -- or, with a [paneOpen]
 * over the selected row, resting on that one too. [selectedIndex] is -1 when the selected id is
 * unknown, so any row is then a change.
 */
internal fun railPendingCommit(
    focusIndex: Int,
    selectedIndex: Int,
    dragging: Boolean,
    paneOpen: Boolean = false,
): Int? =
    if (dragging && (focusIndex != selectedIndex || paneOpen)) focusIndex else null

/** What a wheel that has come to rest on a row means. */
internal enum class RailLanding {
    /** A row other than the selected one: commit it. */
    SELECT,

    /** The selected row, with a pane open over it: the pane is what is being left. */
    LEAVE_PANE,

    /** The selected row and nothing over it: nothing to say. */
    NONE,
}

/**
 * The meaning of resting on [nearest]. With a pane open the selected row is not "no change": the
 * rail's selection is the destination UNDER the pane, so the word you came from is the way back to
 * it ([paneOpen]).
 */
internal fun railLanding(nearest: Int, selectedIndex: Int, paneOpen: Boolean): RailLanding = when {
    nearest != selectedIndex -> RailLanding.SELECT
    paneOpen -> RailLanding.LEAVE_PANE
    else -> RailLanding.NONE
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

/** One hop of the marker from word to word: short, so it keeps up with a turning wheel. */
private const val MARKER_HOP_MS = 140

/**
 * How long the wheel must rest on a new word, finger down or coasting, before that word is
 * committed. Long enough that a fast fling through five rows commits none of the ones it passed
 * over (each commit recomputes the whole library projection), short enough that the pane follows
 * a hesitation instead of waiting for the lift.
 */
internal const val RAIL_COMMIT_DEBOUNCE_MS = 140L

/** The marker square's test tag, so a test can read where it is drawn. */
internal const val RAIL_MARKER_TAG = "destination-rail-marker"

/** How much the square lengthens along its path at mid-flight, and narrows across it. */
private const val MARKER_STRETCH = 0.4f
private const val MARKER_SQUEEZE = 0.12f
