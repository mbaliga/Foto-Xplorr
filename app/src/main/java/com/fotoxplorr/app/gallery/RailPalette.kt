package com.fotoxplorr.app.gallery

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The colours of the destination rail's panel. Two looks: [Dark], the app's black ground and white
 * type that the pinned and collapsed rails on a wide window keep, and [Light], the left room's
 * (owner, 2026-11-08: *"light mode, rounded card, shorter list"*, after the 2026-11-07 reference:
 * light grey ground, near-black type; its faint gradient was dropped because it showed a seam against
 * the flat floor the shell paints around the card).
 *
 * [ink] is the word colour at full strength: the rail's emphasis curve dims the words off the
 * focus line by alpha, so the unselected grey is this ink at that alpha rather than a second
 * colour. Selected state stays size, weight and semantics, never this colour alone.
 */
@Immutable
data class RailPalette(
    val groundStart: Color,
    val groundEnd: Color,
    val ink: Color,
    val hamburger: Color,
    val settings: Color,
) {
    /** The panel's ground: a diagonal wash, flat when both ends are the same. */
    fun groundBrush(): Brush =
        Brush.linearGradient(
            colors = listOf(groundStart, groundEnd),
            start = Offset.Zero,
            end = Offset.Infinite,
        )

    companion object {
        /** The app's own dark look; the pinned and collapsed rails keep it. */
        val Dark = RailPalette(
            groundStart = Color.Black,
            groundEnd = Color.Black,
            ink = Color.White,
            hamburger = Color.White,
            settings = Color.White.copy(alpha = 0.55f),
        )

        /** The left room's look. The shell blends its floor to [RAIL_LIGHT_FLOOR] as the room opens. */
        val Light = RailPalette(
            groundStart = RAIL_LIGHT_FLOOR,
            // The same colour as the floor the shell paints around the card: the panel and the floor are
            // one surface, so there is no seam (owner, 2026-11-09: "the seam is visible, match the
            // colours"). The reference's faint wash had to go: the floor is a flat colour.
            groundEnd = RAIL_LIGHT_FLOOR,
            ink = RAIL_LIGHT_INK,
            hamburger = RAIL_LIGHT_INK,
            settings = RAIL_LIGHT_INK,
        )
    }
}

/** The light ground the shell paints around the parked card and the panel's gradient starts from. */
val RAIL_LIGHT_FLOOR = Color(0xFFDCDEE1)

/** Near-black ink on the light ground: words, hamburger and Settings. */
val RAIL_LIGHT_INK = Color(0xFF1B1C1E)

/**
 * Whether the system bars should draw DARK icons: the light left room is (mostly) open, so a light
 * icon on the light ground would vanish. Open means past halfway; the shell's floor is blended
 * by then. Pure so the threshold is asserted; the window call is a device check.
 */
internal fun leftRoomWantsDarkBars(hProgress: Float): Boolean = hProgress > 0.5f
