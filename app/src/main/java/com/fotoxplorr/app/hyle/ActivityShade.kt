package com.fotoxplorr.app.hyle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A job running in the background that the user is entitled to see.
 *
 * A **list**, not a single "what is the app doing" string, because several genuinely run at once
 * (owner, 2026-08-18: *"this means we need to be able to support multiple activities happening in
 * the background simultaneously"*). A move can be running while the library rescans and recognition
 * grinds through 22,000 photos; the previous model could only ever describe one of those, so the
 * other two were invisible.
 *
 * @param id stable for the lifetime of the job, so the shade can animate one activity finishing
 *   without the others jumping sideways as the list re-indexes.
 * @param completed items done so far. Together with [total] this is the only source of the bar's
 *   length and of the "4,822 of 12,366" readout — one number pair, so they cannot disagree.
 */
data class BackgroundActivity(
    val id: String,
    val kind: ActivityKind,
    val completed: Int = 0,
    val total: Int = 0,
    /** Set when the job failed; the shade shows this instead of a count and stops the bar. */
    val error: String? = null,
) {
    /** 0..1, or null when the total is not yet known and the bar should run indeterminate. */
    val fraction: Float?
        get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    val isFinished: Boolean get() = error == null && total > 0 && completed >= total
}

/**
 * What kind of job it is: its wording, its colour and its glyph.
 *
 * The colour is per-KIND rather than per-activity, and that is what makes the collapsed strip
 * readable — three coloured slivers at the top of the screen say *which three things* are running
 * without a word of text, but only if green always means the same thing. So the rule is one
 * colour = one meaning: no two kinds share a hue, and none borrows the error salmon
 * (`#FFB4AB`), the track grey or the glyph white.
 *
 * Three of the six are the owner's own, sampled from the collapsed-strip reference screenshots
 * (2026-10): green for the save ("Save applies for cloud storage connected devices", which is
 * [BACKING_UP]), blue for the move, orange for the copy — each with the glyph that screenshot
 * draws at the slot's right end. The other three have no reference and take hues that keep the
 * rule: cyan, magenta and yellow sit between the reference hues on the wheel rather than next to
 * any one of them.
 *
 * @param glyph the small white mark beside the kind's bar when the strip shows several jobs at
 *   once. Drawn in a 16-unit viewport with its ink in the middle 12 (see [ActivityGlyphs]) so a
 *   16dp drawing has the reference's 12dp mark.
 */
enum class ActivityKind(val label: String, val accent: Color, val glyph: ImageVector) {
    SCANNING("Reading your library", Color(0xFF00E5FF), ActivityGlyphs.Scan),
    RECOGNISING("Recognising", Color(0xFFE040FB), ActivityGlyphs.Recognise),
    MOVING("Moving", Color(0xFF40A0FF), ActivityGlyphs.Move),
    COPYING("Copying", Color(0xFFFFA100), ActivityGlyphs.Copy),
    BACKING_UP("Backing up", Color(0xFF4DFF00), ActivityGlyphs.Save),
    EXPORTING("Exporting", Color(0xFFFFE600), ActivityGlyphs.Export),
}

/**
 * How much of itself the shade is showing.
 *
 * Three states, and the owner's mockups draw all three. They are a progression in how much room the
 * shade takes from the photographs, and nothing else: the same activities are described at every
 * size.
 */
enum class ShadeState {
    /**
     * Slivers of colour at the very top — one per activity, no words. What a job looks like once
     * you have stopped caring about it, which for a twenty-minute recognition pass is almost all
     * of the time.
     */
    COLLAPSED,

    /** A row per activity: glyph, name, and `4,822 of 12,366`. */
    NOTIFICATION,

    /** The first activity given a hero panel, with any others listed compactly under it. */
    EXPANDED,
}

/**
 * How tall the shade stands, in dp.
 *
 * Pure, and separated from the composable for two reasons. It is the number the host must also
 * reserve from the spatial shell's top-edge gesture — those two disagreeing means either the shade
 * cannot be pulled or the top room cannot be opened. And it is arithmetic with an easy off-by-one
 * in it: the notification state grows per activity and the collapsed one does not.
 */
fun shadeHeight(state: ShadeState, activityCount: Int): Int {
    if (activityCount <= 0) return 0
    return when (state) {
        ShadeState.COLLAPSED -> COLLAPSED_HEIGHT
        // One row each. Capped, because eight simultaneous jobs must not eat the screen — past the
        // cap the shade says "and N more" rather than growing without bound.
        ShadeState.NOTIFICATION ->
            NOTIFICATION_ROW * activityCount.coerceAtMost(MAX_LISTED_ROWS) +
                if (activityCount > MAX_LISTED_ROWS) OVERFLOW_ROW else 0
        ShadeState.EXPANDED ->
            EXPANDED_HERO +
                NOTIFICATION_ROW * (activityCount - 1).coerceAtMost(MAX_LISTED_ROWS) +
                if (activityCount - 1 > MAX_LISTED_ROWS) OVERFLOW_ROW else 0
    }
}

/**
 * Where a drag of [dragDp] from [from] lands.
 *
 * Pure so the gesture's feel is testable. Down opens and up closes, one step at a time: a single
 * long drag must not skip the notification state entirely, because that is the state most drags are
 * actually reaching for.
 */
fun shadeAfterDrag(from: ShadeState, dragDp: Float): ShadeState {
    if (kotlin.math.abs(dragDp) < DRAG_STEP) return from
    val order = ShadeState.entries
    val delta = if (dragDp > 0) 1 else -1
    return order[(order.indexOf(from) + delta).coerceIn(0, order.lastIndex)]
}

/**
 * Collapsed is a sliver: enough colour to notice, not enough to read.
 *
 * 28dp, measured off the owner's collapsed-strip screenshots (2026-10): a black band whose 8dp
 * tracks sit 10dp down, so the strip is exactly as tall as it needs to be to centre them with the
 * same 10dp above and below.
 */
internal const val COLLAPSED_HEIGHT = 28

/** One activity's row in the notification state. */
internal const val NOTIFICATION_ROW = 50

/** The hero panel: illustration, name, count, and a full-width bar under them. */
internal const val EXPANDED_HERO = 250

/** "and 3 more" when there are more jobs than the shade will list. */
internal const val OVERFLOW_ROW = 28

/** Past this many, the shade lists an overflow line instead of another row. */
internal const val MAX_LISTED_ROWS = 4

/** How far a drag must travel to move the shade one state. */
internal const val DRAG_STEP = 24f
