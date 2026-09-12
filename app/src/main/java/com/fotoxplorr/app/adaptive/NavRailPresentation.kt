package com.fotoxplorr.app.adaptive

/**
 * How the nine-destination rail is currently shown.
 *
 * On a phone it has always been a room: a surface parked off the left edge that the home card
 * lifts and parts to reveal, closed the rest of the time. That is the right answer when the
 * screen is one hand wide -- there is no room to spare for a permanent strip. It is the wrong
 * answer on a tablet, a foldable's open screen, or a desktop/ChromeOS window, where a nav rail
 * pinned in place is the everywhere-else convention and pulling it out every time costs a real
 * gesture for no reason. This type is what a screen consults to tell the two apart.
 */
enum class NavRailPresentation {
    /**
     * The rail is a room: hidden until dragged in from the left edge, and the home surface
     * shrinks and turns to reveal it (see `ChromeMotion.SHRINK_AND_PIVOT`). Unconditional on a
     * compact-width window -- there is no user preference that can turn this back on, because
     * a pinned rail on a phone-width screen would not leave enough room for the grid beside it.
     */
    PULL_OUT,

    /** The rail sits beside the content permanently. The default once there is room for it. */
    PINNED,

    /**
     * The rail is still ON SCREEN -- a narrow strip with just the expand affordance -- but its
     * labels and covers are hidden. This is what "persistently visible unless collapsed" (the
     * owner's phrasing) means: collapsing is not the same as returning to [PULL_OUT], which
     * would cost the edge-drag gesture back. A collapsed rail is one tap from pinned again.
     */
    COLLAPSED,
}

/**
 * Decide how the rail should present itself, given how much width is available and whether the
 * person using the app has chosen to collapse it.
 *
 * [userCollapsed] is deliberately ignored on a compact window: it is state the user set on a
 * wider screen (or a previous session on one), and honouring it here would mean rotating a
 * tablet to portrait -- or resizing a desktop window down -- silently swaps the rail for a
 * pull-out room AND remembers that as if the user had asked for it, so rotating back does not
 * restore what they actually had pinned.
 */
fun navRailPresentation(sizeClass: WindowSizeClass, userCollapsed: Boolean): NavRailPresentation =
    when {
        !sizeClass.canPinNavRail -> NavRailPresentation.PULL_OUT
        userCollapsed -> NavRailPresentation.COLLAPSED
        else -> NavRailPresentation.PINNED
    }

/**
 * How the home surface should move when something opens beside or over it -- shrink alone, or
 * shrink AND turn about its hinge edge.
 *
 * This is `dev.aarso.cellshell.ParkStyle` in every way that matters (SLIDE is shrink-only,
 * SWIVEL is shrink-and-turn) but declared fresh here rather than imported: this package is pure
 * Kotlin with no Compose/Android on its classpath, by the task's own hard constraint, and
 * `cellshell`'s type -- however plain the enum itself looks -- lives in a Compose-UI module this
 * package must not depend on. The mapping back to the real type is one `when` at the call site
 * in GalleryScreen, which already imports cellshell for the shell itself.
 */
enum class ChromeMotion {
    /** Shrink only. What the owner asked for once the rail is pinned: "the central pane just
     * shrinks, doesn't pivot". */
    SHRINK_ONLY,

    /** Shrink and turn about the hinge edge -- the "Magic Portal" motion. What a peek (a room
     * opened by dragging it in, on any input method) has always done, and keeps doing. */
    SHRINK_AND_PIVOT,
}

/**
 * The owner's own distinction (2026-08-20): *"in mouse mode user can peek as well -- peek does
 * the shrink and pivot; if nav is turned on, the central pane just shrinks, doesn't pivot, and
 * the nav shows on the left."*
 *
 * Two different things can put the rail on screen, and they must not look the same:
 *  - **Peeking** [NavRailPresentation.PULL_OUT] -- dragging the room in, with a mouse or a
 *    finger -- is inherently a temporary reveal, and the swivel is what sells "temporary": the
 *    card visibly turns away to show you the room, and turning back is how you know it closed.
 *  - **Pinning** [NavRailPresentation.PINNED] or [NavRailPresentation.COLLAPSED] is a standing
 *    choice, not a gesture in progress, so the content beside it should read as a permanent
 *    two-pane layout -- a plain shrink -- rather than replaying a "such-and-such is opening"
 *    animation every time the OTHER rooms (settings, actions, info) are peeked while it is up.
 */
fun chromeMotionFor(navRailPresentation: NavRailPresentation): ChromeMotion = when (navRailPresentation) {
    NavRailPresentation.PULL_OUT -> ChromeMotion.SHRINK_AND_PIVOT
    NavRailPresentation.PINNED, NavRailPresentation.COLLAPSED -> ChromeMotion.SHRINK_ONLY
}
