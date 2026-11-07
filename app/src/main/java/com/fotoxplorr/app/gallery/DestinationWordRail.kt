package com.fotoxplorr.app.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.HyleGrotesk
import dev.aarso.cellshell.SpatialMotion
import dev.aarso.cellshell.WheelItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The destinations as the owner's left-room reference (2026-11-07) draws them: a short run of
 * words down the left of the room, the one on the focus line large (24sp medium) and full white,
 * every other word smaller (17sp) in one uniform grey. No marker: the reference has none, and the
 * current word is told by its size and weight (and `selected` semantics), not by colour alone.
 *
 * An app-local wheel, not `dev.aarso:cell-shell`'s `WordWheelRail`. The shared rail's type, its
 * distance fade and its marker box are private constants a host cannot reach, and none of them is
 * the reference's. Restyling through parameters was not possible, so this is the same wheel
 * re-cut; the shared library is untouched.
 *
 * The wheel's BEHAVIOUR is kept, because the owner's earlier reference video set it and a still
 * cannot unset it: the run turns under the finger, and the emphasis is read off the fractional
 * scroll position, so a word grows and brightens continuously as it reaches the focus line rather
 * than re-highlighting on settle ([destinationRowEmphasis]).
 *
 * Owner, 2026-10: *"release should not take you to a location -- if scroll and release is the
 * interaction, then the selection indicator must move as well, and the change should reflect on
 * the pane"*. The emphasis tracks the focus line LIVE, and the hovered destination is committed
 * once the wheel hesitates ([RAIL_COMMIT_DEBOUNCE_MS]) -- while the finger is still down -- so the
 * pane follows it. Lifting the finger only SETTLES the wheel on its nearest word; it never
 * navigates and never closes the room (that is [onReselect]'s job).
 *
 * **Nothing per-frame recomposes.** Every row is laid out once at the focus style; its scale
 * (about the word's start, so the left edge holds) and alpha are read in its graphics layer, so a
 * turn of the wheel is draw-phase work only. The cost is that a word off the line is the medium
 * face scaled down rather than a true regular cut -- weight cannot change without re-laying-out.
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
 * @param onTurning whether the wheel is moving at all: a finger on it, its fling, or the rail's
 *   own settle. A host that creates something costly from the committed word (the street map)
 *   waits for false, because a commit arrives while the finger is still down.
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
    onTurning: (Boolean) -> Unit = {},
) {
    if (items.isEmpty()) return

    // -1 when the id is unknown: nothing is selected, so any hovered row is a change.
    val selectedRaw = items.indexOfFirst { it.id == selectedId }
    val selectedIndex = selectedRaw.coerceAtLeast(0)
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier.testTag(RAIL_WHEEL_TAG)) {
        val density = LocalDensity.current

        // The pitch is in sp, not dp, so the wheel grows with the user's font scale instead of
        // clipping 24sp type into a 40dp box. Every row is pinned to exactly this height, which
        // is what lets the focus-line geometry be arithmetic on the scroll offset.
        val pitchDp = with(density) { RAIL_ROW_PITCH_SP.sp.toDp() }
        val pitchPx = with(density) { pitchDp.toPx() }

        // Half a viewport of blank above and below, so the first and last words can reach the
        // focus line like any other. Unbounded height has no centre; fall back to one row.
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else pitchPx
        val padPx = ((viewportPx - pitchPx) / 2f).coerceAtLeast(0f)
        val padDp = with(density) { padPx.toDp() }

        // With that padding, one pitch of scroll moves the focus line one row, so this IS the
        // wheel's position in row units, fractional part and all.
        fun scrollRows(): Float = scroll.value / pitchPx

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

        // The wheel's motion, for the host. Reported from here rather than derived by it because
        // only the rail knows which scrolls count: all of them, the rail's own settle included.
        val currentOnTurning by rememberUpdatedState(onTurning)
        LaunchedEffect(scroll) {
            snapshotFlow { dragging || scroll.isScrollInProgress }.collect { currentOnTurning(it) }
        }
        // A rail that leaves the composition mid-turn (a window resize swapping presentations)
        // must not leave the host believing it is still turning.
        DisposableEffect(scroll) { onDispose { currentOnTurning(false) } }

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

        // That settle can be cut short. Lifting a finger that had stopped still starts a
        // zero-velocity fling, which outranks the settle the release has just begun, cancels it
        // and ends at once -- leaving the wheel between two words with no turn left to land. (The
        // square marker hid this: it was drawn where the row was, not where the detent is.) So
        // whenever the wheel has stayed still off a detent for a moment with no finger on it,
        // settle it on the nearest row. collectLatest drops the wait the moment anything moves it.
        LaunchedEffect(scroll, pitchPx) {
            snapshotFlow { placed && !dragging && !scroll.isScrollInProgress }.collectLatest { resting ->
                if (!resting) return@collectLatest
                delay(RAIL_REST_SETTLE_MS)
                val nearest = railFocusIndex(scroll.value, pitchPx, currentItems.lastIndex)
                val detent = (nearest * pitchPx).roundToInt()
                // Launched in the effect, not the wait, so the settle's own motion does not cancel it.
                if (scroll.value != detent) this@LaunchedEffect.launch { moveWheelTo(detent, animated = true) }
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
    }
}

/**
 * One word in the run. Its own composable so it has its own recomposition scope -- though with
 * one style for every row, a turn of the wheel never invalidates it at all.
 */
@Composable
private fun DestinationRow(
    item: WheelItem,
    isSelected: Boolean,
    rowHeight: Dp,
    distance: () -> Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: the rail is flat type on a flat ground, and touch chrome would be
                // the only box on the surface.
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            // Selection is told by size, weight AND this, never by the white alone.
            .semantics { selected = isSelected },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The room left for the word is what decides its size: "Experimental" at the focus size
        // can be wider than a narrow room (the open park leaves about 190 dp on a 360 dp phone),
        // so a word that does not fit is drawn smaller (down to RAIL_MIN_FONT_SP) before it is
        // ever cut. Every other word keeps the focus size, and the emphasis below scales it.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val availablePx = constraints.maxWidth.toFloat()
            val baseStyle = TextStyle(
                color = Color.White,
                fontFamily = HyleGrotesk,
                fontSize = RAIL_FONT_SP.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = RAIL_LETTER_SPACING_SP.sp,
            )
            val fontSp = remember(item.label, availablePx, density.density, density.fontScale) {
                val natural = measurer.measure(
                    text = item.label,
                    style = baseStyle,
                    softWrap = false,
                    maxLines = 1,
                ).size.width.toFloat()
                railFontSpFor(naturalPx = natural, availablePx = availablePx)
            }
            BasicText(
                text = item.label,
                style = baseStyle.copy(fontSize = fontSp.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    // Read on the draw pass: the lambda re-runs when the scroll offset changes,
                    // so the emphasis follows the focus line continuously and nothing recomposes.
                    val emphasis = destinationRowEmphasis(distance())
                    scaleX = emphasis.scale
                    scaleY = emphasis.scale
                    alpha = emphasis.alpha
                    // Scaled about the word's start, so every word stays on one left edge.
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
        }
    }
}

/**
 * The font size, in sp, to draw a rail word at: [baseSp] (the focus size) when its natural width
 * [naturalPx] (measured at [baseSp]) fits in [availablePx], otherwise scaled down to fit, with a
 * 2% margin for kerning the measure does not see, and never below [minSp]. Pure so the rule is
 * asserted, not eyeballed. A word that cannot fit even at the floor is ellipsised by the text.
 */
internal fun railFontSpFor(
    naturalPx: Float,
    availablePx: Float,
    baseSp: Float = RAIL_FONT_SP.toFloat(),
    minSp: Float = RAIL_MIN_FONT_SP.toFloat(),
): Float =
    if (naturalPx <= 0f || naturalPx <= availablePx) baseSp
    else (baseSp * availablePx / naturalPx * 0.98f).coerceAtLeast(minSp)

/** How a row is drawn: its scale against the focus size, and its alpha of white. */
internal data class RowEmphasis(val scale: Float, val alpha: Float)

/**
 * The emphasis of a row [distance] rows from the focus line, fractional while the wheel turns.
 *
 * The owner's reference (2026-11-07) has two looks, not a fade: the current word large and full
 * (24sp), every other word smaller (17sp) in one uniform grey. So the curve runs linearly from
 * the focus look at 0 to the rest look at one row and holds it beyond -- a word two rows out is
 * no dimmer than one row out. Interpolated rather than stepped so a word grows as it reaches the
 * line under the finger instead of re-highlighting on settle. Pure so it is asserted.
 */
internal fun destinationRowEmphasis(distance: Float): RowEmphasis {
    val t = abs(distance).coerceAtMost(1f)
    return RowEmphasis(
        scale = 1f + (RAIL_REST_SCALE - 1f) * t,
        alpha = 1f + (RAIL_REST_ALPHA - 1f) * t,
    )
}

/**
 * The row under the focus line for a scroll offset: with the wheel's half-viewport padding one
 * [pitchPx] of scroll moves the focus line exactly one row, so this is the offset in rows, rounded
 * to the nearest. The single definition for the emphasis's row, the debounced commit and the
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

/** The reference's focus word: 24sp medium. A long word may be fitted smaller, see [railFontSpFor]. */
internal const val RAIL_FONT_SP = 24

/** The reference's other words: 17sp, drawn as the focus style scaled by [RAIL_REST_SCALE]. */
internal const val RAIL_REST_FONT_SP = 17
internal const val RAIL_REST_SCALE = RAIL_REST_FONT_SP.toFloat() / RAIL_FONT_SP

/** The one grey every word off the focus line is drawn in, as an alpha of white on black. */
internal const val RAIL_REST_ALPHA = 0.5f

/** The smallest a long word is shrunk to by [railFontSpFor]: still above the rest size. */
internal const val RAIL_MIN_FONT_SP = 18

/** About 40 apart, as the reference sets its rows. In sp, so the pitch grows with the type. */
internal const val RAIL_ROW_PITCH_SP = 40
private const val RAIL_LETTER_SPACING_SP = -0.2f

/**
 * How long the wheel must rest on a new word, finger down or coasting, before that word is
 * committed. Long enough that a fast fling through five rows commits none of the ones it passed
 * over (each commit recomputes the whole library projection), short enough that the pane follows
 * a hesitation instead of waiting for the lift.
 */
internal const val RAIL_COMMIT_DEBOUNCE_MS = 140L

/** How long the wheel must sit still off a detent before it is settled again. About three frames. */
private const val RAIL_REST_SETTLE_MS = 48L

/** The wheel's test tag: its vertical centre is the focus line. */
internal const val RAIL_WHEEL_TAG = "destination-rail-wheel"
