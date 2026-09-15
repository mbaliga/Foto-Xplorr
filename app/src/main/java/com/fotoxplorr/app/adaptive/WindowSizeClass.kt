package com.fotoxplorr.app.adaptive

/**
 * Where a window's WIDTH sits on the compact/medium/expanded ladder.
 *
 * These are not this app's own numbers. They are Google's published window-size-class
 * breakpoints (600dp and 840dp), copied deliberately rather than invented, because the
 * intended real fix here is `androidx.compose.material3.windowsizeclass` -- and it is not on
 * this build's classpath (verified against the resolved `material3` artifacts before writing
 * this file, per WP1's hard constraint: no new dependency may be added to reach it). Matching
 * the real library's thresholds means the day it IS added, swapping this file's call sites for
 * the real type is a rename, not a redesign, and nothing that already shipped against these
 * numbers has to be re-tuned.
 */
enum class WidthSizeClass {
    /** A phone in portrait. Below 600dp: the nav rail cannot be persistent here -- see below. */
    COMPACT,

    /** A phone in landscape, most foldables unfolded, a small split-screen pane. 600..839dp. */
    MEDIUM,

    /** A tablet, a foldable's full inner screen, or a desktop/ChromeOS window. 840dp and up. */
    EXPANDED,
}

/** Where a window's HEIGHT sits on the same ladder. Mirrors [WidthSizeClass]'s own breakpoints. */
enum class HeightSizeClass {
    /** Below 480dp -- a phone rotated to landscape is the common case that lands here. */
    COMPACT,

    /** 480..899dp -- most phones in portrait, most tablets in landscape. */
    MEDIUM,

    /** 900dp and up -- a tablet in portrait, most desktop and ChromeOS windows. */
    EXPANDED,
}

/**
 * The two-axis size class for one window measurement, and the handful of yes/no questions the
 * rest of the adaptive package needs answered about it.
 *
 * Width and height are classified independently and on purpose: a phone rotated to landscape is
 * WIDTH-medium but HEIGHT-compact, and a layout decision that only ever reads width would put a
 * persistent rail on a screen 380dp tall, where it would take a third of the usable height.
 */
data class WindowSizeClass(
    val width: WidthSizeClass,
    val height: HeightSizeClass,
) {
    /**
     * Room enough for a nav rail to live beside the content permanently rather than as a
     * pull-out room. Width-gated only -- the rail is a vertical strip, so it is the WIDTH that
     * is scarce on a compact phone, not the height.
     */
    val canPinNavRail: Boolean
        get() = width != WidthSizeClass.COMPACT
}

/**
 * Classify a window's width in dp against the [WidthSizeClass] breakpoints.
 *
 * A free function rather than a method on some Android `Configuration` wrapper, and taking a
 * plain `Float` rather than a `Dp`, so it can be unit-tested on the JVM with no Compose UI and no
 * Robolectric -- the whole reason this file exists instead of reaching for the real
 * `windowsizeclass` artifact.
 */
fun widthSizeClassOf(widthDp: Float): WidthSizeClass = when {
    widthDp < WIDTH_MEDIUM_BREAKPOINT_DP -> WidthSizeClass.COMPACT
    widthDp < WIDTH_EXPANDED_BREAKPOINT_DP -> WidthSizeClass.MEDIUM
    else -> WidthSizeClass.EXPANDED
}

/** Classify a window's height in dp against the [HeightSizeClass] breakpoints. */
fun heightSizeClassOf(heightDp: Float): HeightSizeClass = when {
    heightDp < HEIGHT_MEDIUM_BREAKPOINT_DP -> HeightSizeClass.COMPACT
    heightDp < HEIGHT_EXPANDED_BREAKPOINT_DP -> HeightSizeClass.MEDIUM
    else -> HeightSizeClass.EXPANDED
}

/** Classify both axes of a window at once. What every call site actually wants. */
fun windowSizeClassOf(widthDp: Float, heightDp: Float): WindowSizeClass =
    WindowSizeClass(widthSizeClassOf(widthDp), heightSizeClassOf(heightDp))

private const val WIDTH_MEDIUM_BREAKPOINT_DP = 600f
private const val WIDTH_EXPANDED_BREAKPOINT_DP = 840f
private const val HEIGHT_MEDIUM_BREAKPOINT_DP = 480f
private const val HEIGHT_EXPANDED_BREAKPOINT_DP = 900f
