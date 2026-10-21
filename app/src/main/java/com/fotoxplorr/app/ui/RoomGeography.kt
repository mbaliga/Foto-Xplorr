package com.fotoxplorr.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
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
 * App-wide Settings is not a room: it is a pane that takes over the place the photos are.
 */
object RoomGeography {
    val Navigation: RoomEdge = RoomEdge.LEFT
    val Details: RoomEdge = RoomEdge.TOP
    val Actions: RoomEdge = RoomEdge.BOTTOM
    val ViewOptions: RoomEdge = RoomEdge.RIGHT

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
 * The system bar a room on [edge] has to clear: the status bar for a room that comes in from the
 * top or the side, the navigation bar for one that comes up from the bottom. The shell insets a
 * room by its own band on the side the parked card sits, so this is only ever the OTHER end, and
 * it follows the edge, not the room: move a room and its padding moves with it.
 */
fun Modifier.clearSystemBarsFor(edge: RoomEdge): Modifier = when (edge) {
    RoomEdge.BOTTOM -> navigationBarsPadding()
    else -> statusBarsPadding()
}
