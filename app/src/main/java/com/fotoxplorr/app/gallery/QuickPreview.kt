package com.fotoxplorr.app.gallery

/**
 * The pure core of the grid's "look without opening" family (plan WP2.10): mouse hover, the
 * hover-scrub of a video, space-bar Quick Look and the always-open preview pane. No Compose and no
 * clock of its own: the caller passes the time, so every rule here runs on the JVM.
 * The Compose seam is QuickPreviewHost.kt.
 */

/** How long the pointer rests on a tile before the enlarged preview shows (plan: 400 ms). */
internal const val HOVER_PREVIEW_DELAY_MS = 400L

/** Narrowest window that offers the two-pane layout (plan: >= 600 dp). */
internal const val PREVIEW_PANE_MIN_WIDTH_DP = 600

/**
 * The hover preview: nothing, a tile the pointer has just entered (waiting out the delay), or the
 * preview showing. Ids are the grid's own item ids.
 */
internal sealed interface HoverPreview {
    data object Idle : HoverPreview
    data class Pending(val id: Long, val sinceMs: Long) : HoverPreview
    data class Showing(val id: Long) : HoverPreview
}

/** The id to draw large, or null while nothing is showing. */
internal fun HoverPreview.shownId(): Long? = (this as? HoverPreview.Showing)?.id

/** The pointer entered or moved onto tile [id]; moving between tiles restarts the wait. */
internal fun HoverPreview.onEnter(id: Long, nowMs: Long): HoverPreview = when {
    this is HoverPreview.Showing && this.id == id -> this
    this is HoverPreview.Pending && this.id == id -> this
    else -> HoverPreview.Pending(id, nowMs)
}

/** Time passed with the pointer still down on the same tile. */
internal fun HoverPreview.onTick(nowMs: Long): HoverPreview =
    if (this is HoverPreview.Pending && nowMs - sinceMs >= HOVER_PREVIEW_DELAY_MS) HoverPreview.Showing(id) else this

/**
 * Waits out a [HoverPreview.Pending] on [now] and returns the state it settles in. Loops rather
 * than sleeping once, so a sleep that wakes a millisecond early (or a clock that reads behind the
 * sleep's own) re-waits the remainder instead of leaving the preview pending for good. Always
 * sleeps at least 1 ms, so it cannot spin.
 */
internal suspend fun HoverPreview.awaitShown(now: () -> Long, sleep: suspend (Long) -> Unit): HoverPreview {
    var s = this
    while (s is HoverPreview.Pending) {
        sleep((s.remainingMs(now()) ?: 0L).coerceAtLeast(1L))
        s = s.onTick(now())
    }
    return s
}

/** The pointer left the tile, a press began, or the grid scrolled: all drop the preview. */
internal fun HoverPreview.onDismiss(): HoverPreview = HoverPreview.Idle

/** Milliseconds still to wait before [HoverPreview.Pending] becomes showing; null if not pending. */
internal fun HoverPreview.remainingMs(nowMs: Long): Long? =
    (this as? HoverPreview.Pending)?.let { (HOVER_PREVIEW_DELAY_MS - (nowMs - it.sinceMs)).coerceAtLeast(0L) }

/**
 * Hover-scrub of a video: the pointer's x across the tile picks a keyframe of the cached strip.
 * Clamped, so a pointer on the very edge or a zero-width tile never indexes outside the strip.
 */
internal fun hoverScrubFrame(x: Float, tileWidth: Float, frameCount: Int): Int {
    if (frameCount <= 1 || tileWidth <= 0f) return 0
    val f = (x / tileWidth).coerceIn(0f, 1f)
    return (f * frameCount).toInt().coerceAtMost(frameCount - 1)
}

/** Keys Quick Look answers to; kept local so the shared shortcut table is not widened. */
internal enum class QuickLookKey { SPACE, ESCAPE, LEFT, RIGHT, UP, DOWN, ENTER }

/**
 * Space-bar Quick Look over the grid's items: [index] is the previewed item or null when closed.
 * [columns] gives Up/Down their step; the preview never wraps and never leaves the list.
 */
internal data class QuickLook(val index: Int? = null) {
    val isOpen: Boolean get() = index != null
}

/** What a key did to Quick Look. [openViewer] asks the caller to open the full viewer on [QuickLook.index]. */
internal data class QuickLookResult(val state: QuickLook, val consumed: Boolean, val openViewer: Boolean = false)

/**
 * @param focused the grid's keyboard-focused item index, opened by Space when closed.
 * An unhandled key while closed is not consumed so the grid's own keys (arrows) keep working.
 */
internal fun QuickLook.onKey(key: QuickLookKey, focused: Int?, count: Int, columns: Int): QuickLookResult {
    val cur = index
    if (cur == null) {
        return if (key == QuickLookKey.SPACE && focused != null && focused in 0 until count) {
            QuickLookResult(QuickLook(focused), consumed = true)
        } else {
            QuickLookResult(this, consumed = false)
        }
    }
    val step = columns.coerceAtLeast(1)
    val next = when (key) {
        QuickLookKey.SPACE, QuickLookKey.ESCAPE -> return QuickLookResult(QuickLook(null), consumed = true)
        QuickLookKey.ENTER -> return QuickLookResult(QuickLook(null), consumed = true, openViewer = true)
        QuickLookKey.LEFT -> cur - 1
        QuickLookKey.RIGHT -> cur + 1
        QuickLookKey.UP -> cur - step
        QuickLookKey.DOWN -> cur + step
    }
    return QuickLookResult(QuickLook(if (next in 0 until count) next else cur), consumed = true)
}

/** An item removed or filtered away under an open Quick Look: clamp, or close when the list is empty. */
internal fun QuickLook.clampedTo(count: Int): QuickLook = when {
    index == null -> this
    count <= 0 -> QuickLook(null)
    index >= count -> QuickLook(count - 1)
    else -> this
}

/** Whether the grid + large preview + info layout is on: wide enough AND the user asked for it. */
internal fun previewPaneShown(widthDp: Int, userToggle: Boolean): Boolean =
    userToggle && widthDp >= PREVIEW_PANE_MIN_WIDTH_DP

/** The pane's width beside the grid: a third of the window, kept readable and never greedy. */
internal fun previewPaneWidthDp(windowWidthDp: Int): Int = (windowWidthDp * 0.35f).toInt().coerceIn(280, 480)

/** Whether the toggle (the pill button and the `P` key) is offered at all. */
internal fun previewPaneOffered(widthDp: Int): Boolean = widthDp >= PREVIEW_PANE_MIN_WIDTH_DP

/**
 * Which item the pane shows: the last item the user focused or tapped, falling back to the first
 * selected one, kept inside the list. Null when there is nothing to show.
 */
internal fun previewPaneItem(focused: Int?, firstSelected: Int?, count: Int): Int? =
    listOfNotNull(focused, firstSelected).firstOrNull { it in 0 until count }

/** One short sentence per control, as the product rule asks. */
internal object QuickPreviewCopy {
    const val TOGGLE_PANE = "Show the preview pane."
    const val HIDE_PANE = "Hide the preview pane."
    const val PANE_EMPTY = "Pick a photo to preview it."
    const val QUICK_LOOK_CLOSE = "Close the preview."
    const val PREVIOUS = "Previous photo."
    const val NEXT = "Next photo."
    const val OPEN = "Open"
}
