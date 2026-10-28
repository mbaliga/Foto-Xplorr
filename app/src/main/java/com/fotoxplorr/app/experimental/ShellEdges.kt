package com.fotoxplorr.app.experimental

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a full-screen interactive experiment must not start a drag (TRAPS #72).
 *
 * The spatial shell takes a horizontal drag that starts within its edge zone of the left or right
 * screen edge, and a vertical drag within it of the top or bottom edge, on the Initial pass --
 * before any content sees the pointer. A drawing board, a wall editor or a live view that reaches
 * the screen edge therefore loses the first stroke that begins there to a room opening. The fix
 * is to keep the part that takes drags out of the zones: put [clearOfShellEdges] on the
 * interactive area (not on its background, which may still bleed to the edge).
 *
 * The shell starts its top zone below the status bar and ends its bottom zone where the
 * navigation bar or gesture inset begins, so [clearOfShellEdges] adds those system insets (only
 * what a parent has not already consumed) before the zone itself. It does not know a host's own
 * `topReserve` or `bottomReserve`: the app passes none, so there is nothing to add today.
 *
 * [ZONE_DP] mirrors the shell's `EDGE_DP`, which is private to that library; `ShellEdgesTest` reads
 * the library's source and fails if the two differ. [MARGIN_DP] is slack on top for a finger that
 * lands just inside the zone. Nothing here has been tried on a device.
 */
object ShellEdges {
    /** The shell's edge zone, in dp (mirror of its private `EDGE_DP`). */
    const val ZONE_DP = 56f

    /** Extra clearance past [ZONE_DP]. */
    const val MARGIN_DP = 4f

    /** What an interactive area is kept from each screen edge, beyond the system bars. */
    const val INSET_DP = ZONE_DP + MARGIN_DP

    /** The inset as padding values, for code that lays out by hand. */
    val padding: PaddingValues = PaddingValues(INSET_DP.dp)

    /** The inset as a size, for a computed rectangle. */
    val inset: Dp = INSET_DP.dp
}

/**
 * Keeps this area [ShellEdges.INSET_DP] from every side, and below the status bar and above the
 * navigation bar, so no drag in it starts in a shell edge zone. Put it on the area that takes the
 * drags, e.g. `Modifier.fillMaxSize().clearOfShellEdges().pointerInput(...)`.
 */
fun Modifier.clearOfShellEdges(): Modifier = composed {
    val system = WindowInsets.systemBars.union(WindowInsets.systemGestures).only(WindowInsetsSides.Vertical)
    Modifier.windowInsetsPadding(system).padding(ShellEdges.padding)
}

/**
 * Whether a point ([x], [y]) of a [width] x [height] area lies in a shell edge zone of [zonePx]
 * from each side. Pure, for tests and for a handler that wants to ignore such a start itself
 * (the area then has to be the whole window, so it is measured from the screen edges).
 */
fun inShellEdgeZone(x: Float, y: Float, width: Float, height: Float, zonePx: Float): Boolean =
    x < zonePx || x > width - zonePx || y < zonePx || y > height - zonePx
