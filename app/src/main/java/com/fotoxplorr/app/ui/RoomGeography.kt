package com.fotoxplorr.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.aarso.cellshell.RoomEdge

/** What a room is FOR. The edge a kind lives on is [RoomGeography]'s to say, nobody else's. */
enum class RoomKind {
    /** Where you can go: the destination rail. */
    NAVIGATION,

    /** What this is: the grid's counts, the open photo's facts. */
    DETAILS,

    /** What you can do with it. */
    ACTIONS,

    /** How it is shown. */
    VIEW_OPTIONS,
}

/**
 * The app's one room geography: which edge holds which kind of room, in the gallery and the
 * viewer alike.
 *
 * | edge   | holds                                                          |
 * |--------|----------------------------------------------------------------|
 * | LEFT   | where you can go: the destination rail                         |
 * | TOP    | details: the view's counts, or the open photo's facts          |
 * | BOTTOM | actions: what you can do, tilting up from under the content    |
 * | RIGHT  | view options: how it is shown (columns, sort, filmstrip, ...)  |
 *
 * The reasoning is the owner's (batch 2 feedback, #26). View options sit at the side because you want to
 * watch their effect, whereas an action does not need the content in view. Actions sit at the
 * bottom because tilting the content up to reveal them is the better motion for something you do
 * once and leave.
 *
 * This replaces three hand-kept tables that had drifted: the gallery had settings on top, actions
 * on the right and details at the bottom, while the viewer had details on top, actions on the
 * right and settings at the bottom. Both screens now build their shell slots through [roomSlots]
 * and open rooms through these constants, so moving a kind here moves it everywhere, and a screen
 * cannot disagree with the other without a test noticing.
 *
 * The BOTTOM room is also the one two-stage room: a short pull stops on its quick-action strip
 * ([ActionsPeek]) and a longer one carries on to every action. That is a property of the actions
 * kind, so it lives in this table too; the screens read it through [peekFor] and never pick a
 * height themselves.
 *
 * App-wide Settings is not a room: it is a pane that takes over the place the photos are.
 */
object RoomGeography {
    val Navigation: RoomEdge = RoomEdge.LEFT
    val Details: RoomEdge = RoomEdge.TOP
    val Actions: RoomEdge = RoomEdge.BOTTOM
    val ViewOptions: RoomEdge = RoomEdge.RIGHT

    /**
     * How much room the shell is told to show on a short pull of the actions room: the quick-action
     * strip and nothing else.
     *
     * The shell counts this as the usable height BELOW the head band it holds back at the top of
     * a bottom room (`SpatialMotion.BAND_DP`, 72dp), and adds that band itself, as well as the
     * navigation bar's height. So the number is the strip's own height, with no band of ours
     * mirrored beside it. The strip is anchored to the top of the room's content, which is what
     * arrives first. The navigation bar's share of the peek is filled by [ActionsRoomFrame] with
     * black, so the first rows of the list do not show under the strip.
     */
    val ActionsPeek: Dp = QuickActionStripHeight

    /**
     * The peek for the room of [kind], or null when that room is single-stage. Only the actions
     * room is staged, and the shell can stage only its bottom axis, so asking for any other kind
     * (or for actions after it has moved off the bottom) is null rather than a wrong answer.
     * Screens pass this straight to the shell's `bottomPeek`.
     */
    fun peekFor(kind: RoomKind): Dp? =
        if (kind == RoomKind.ACTIONS && Actions == RoomEdge.BOTTOM) ActionsPeek else null

    /** The edge that holds [kind]. */
    fun edgeFor(kind: RoomKind): RoomEdge = when (kind) {
        RoomKind.NAVIGATION -> Navigation
        RoomKind.DETAILS -> Details
        RoomKind.ACTIONS -> Actions
        RoomKind.VIEW_OPTIONS -> ViewOptions
    }

    /** The kind of room [edge] holds, or null for [RoomEdge.HOME] (no room). */
    fun kindAt(edge: RoomEdge): RoomKind? = RoomKind.entries.firstOrNull { edgeFor(it) == edge }
}

/** The four slots a spatial shell takes, filled by kind. A null slot is an edge with no room. */
class RoomSlots<T : Any>(val left: T?, val top: T?, val right: T?, val bottom: T?)

/**
 * The slots as they stand while a pane (Settings, a destination screen) has taken over the place
 * the photos are: navigation stays, so the rail can still lead back out, and every other kind is
 * dropped. Their rooms describe and act on the grid underneath, which nobody can see, and a null
 * slot makes the shell refuse the drag from that edge instead of opening a room onto nothing.
 * Which slot is navigation is [RoomGeography]'s to say, not this function's.
 */
fun <T : Any> RoomSlots<T>.navigationOnlyWhen(paneTakenOver: Boolean): RoomSlots<T> {
    if (!paneTakenOver) return this
    fun keep(edge: RoomEdge, room: T?): T? = if (RoomGeography.kindAt(edge) == RoomKind.NAVIGATION) room else null
    return RoomSlots(
        left = keep(RoomEdge.LEFT, left),
        top = keep(RoomEdge.TOP, top),
        right = keep(RoomEdge.RIGHT, right),
        bottom = keep(RoomEdge.BOTTOM, bottom),
    )
}

/**
 * Put each kind's room on the edge [RoomGeography] assigns it. Both screens pass the result to
 * the shell as `left = slots.left`, `top = slots.top` and so on, instead of choosing an edge for
 * each room themselves; a kind left null leaves its edge without a room (the shell then refuses
 * drags from it).
 */
fun <T : Any> roomSlots(
    navigation: T? = null,
    details: T? = null,
    actions: T? = null,
    viewOptions: T? = null,
): RoomSlots<T> {
    val byKind = mapOf(
        RoomKind.NAVIGATION to navigation,
        RoomKind.DETAILS to details,
        RoomKind.ACTIONS to actions,
        RoomKind.VIEW_OPTIONS to viewOptions,
    )
    fun at(edge: RoomEdge): T? = RoomGeography.kindAt(edge)?.let { byKind[it] }
    return RoomSlots(
        left = at(RoomEdge.LEFT),
        top = at(RoomEdge.TOP),
        right = at(RoomEdge.RIGHT),
        bottom = at(RoomEdge.BOTTOM),
    )
}

/**
 * The system bars a room on [edge] has to clear. A TOP room comes down from under the status bar
 * and a BOTTOM room tilts up to the navigation bar, so each clears only the bar it touches. A LEFT
 * or RIGHT room runs the full height of the window, so it touches both and clears both: the status
 * bar at its head and the navigation bar at its foot. (A side room used to clear only the status
 * bar, which left its last row under the gesture bar.)
 *
 * The shell insets a room by its own band on the side the parked card sits, so this is only ever
 * the OTHER ends, and it follows the edge, not the room: move a room and its padding moves with
 * it.
 */
fun Modifier.clearSystemBarsFor(edge: RoomEdge): Modifier = when (systemBarsClearedBy(edge)) {
    SystemBarsCleared.STATUS -> statusBarsPadding()
    SystemBarsCleared.NAVIGATION -> navigationBarsPadding()
    SystemBarsCleared.BOTH -> statusBarsPadding().navigationBarsPadding()
}

/** Which system bars a room clears; the choice [clearSystemBarsFor] makes, apart from the modifiers. */
enum class SystemBarsCleared { STATUS, NAVIGATION, BOTH }

/** The bars a room on [edge] clears -- see [clearSystemBarsFor]. */
fun systemBarsClearedBy(edge: RoomEdge): SystemBarsCleared = when (edge) {
    RoomEdge.TOP -> SystemBarsCleared.STATUS
    RoomEdge.BOTTOM -> SystemBarsCleared.NAVIGATION
    RoomEdge.LEFT, RoomEdge.RIGHT -> SystemBarsCleared.BOTH
    // No room lives at HOME; the status bar is the safe default for whatever is drawn there.
    else -> SystemBarsCleared.STATUS
}
