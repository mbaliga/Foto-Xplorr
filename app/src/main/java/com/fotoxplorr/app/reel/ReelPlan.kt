package com.fotoxplorr.app.reel

/**
 * What can be wrong with a reel plan. A [blocking] problem stops the render (the job would have to
 * guess); the others are shown and the render goes ahead. The sentence for each is in [ReelCopy].
 */
enum class ReelIssueKind(val blocking: Boolean) {
    NO_ITEMS(true),
    TOO_MANY_ITEMS(true),
    TOO_LONG(true),
    DUPLICATE_ID(true),
    ITEM_TOO_SHORT(true),
    ITEM_TOO_LONG(true),
    CLIP_RANGE(true),
    CLIP_PAST_END(true),
    MOTION_RECT(true),
    MOTION_FOCUS(true),
    SOUNDTRACK(true),
    TITLE_EMPTY(false),
}

/** One problem, with the 0-based [itemIndex] it is about, or null when it is about the whole plan. */
data class ReelIssue(val kind: ReelIssueKind, val itemIndex: Int? = null) {
    val blocking: Boolean get() = kind.blocking
}

/**
 * The check a plan passes before it is rendered, and again after every edit so the editor can mark
 * the item at fault. Pure and total: it never throws, and an empty list means nothing is wrong.
 *
 * It checks the PLAN only. Whether a photo still exists, may be shown, or can be decoded is found
 * out by the renderer, one item at a time, because that can change between the edit and the render.
 */
object ReelPlan {

    fun validate(project: ReelProject): List<ReelIssue> {
        val issues = ArrayList<ReelIssue>()
        if (project.items.isEmpty()) issues += ReelIssue(ReelIssueKind.NO_ITEMS)
        if (project.items.size > ReelLimits.MAX_ITEMS) issues += ReelIssue(ReelIssueKind.TOO_MANY_ITEMS)

        val seen = HashSet<String>()
        project.items.forEachIndexed { index, item ->
            if (!seen.add(item.id)) issues += ReelIssue(ReelIssueKind.DUPLICATE_ID, index)
            if (item.durationMs < ReelLimits.MIN_ITEM_MS) issues += ReelIssue(ReelIssueKind.ITEM_TOO_SHORT, index)
            if (item.durationMs > ReelLimits.ITEM_MAX_MS) issues += ReelIssue(ReelIssueKind.ITEM_TOO_LONG, index)
            when (item) {
                is StillItem -> checkMotion(item.motion, index, issues)
                is ClipItem -> {
                    if (item.inMs < 0L || item.outMs <= item.inMs) {
                        issues += ReelIssue(ReelIssueKind.CLIP_RANGE, index)
                    } else if (item.sourceDurationMs > 0L && item.outMs > item.sourceDurationMs) {
                        issues += ReelIssue(ReelIssueKind.CLIP_PAST_END, index)
                    }
                }
                is TitleItem -> if (item.text.isBlank()) issues += ReelIssue(ReelIssueKind.TITLE_EMPTY, index)
            }
        }

        if (project.totalDurationMs > ReelLimits.MAX_TOTAL_MS) issues += ReelIssue(ReelIssueKind.TOO_LONG)

        project.soundtrack?.let {
            if (it.uri.isBlank() || it.trimStartMs < 0L || it.fadeOutMs < 0L || it.fadeOutMs > ReelLimits.FADE_OUT_MAX_MS) {
                issues += ReelIssue(ReelIssueKind.SOUNDTRACK)
            }
        }
        return issues
    }

    /** True when no problem [ReelIssue.blocking] is in [issues]. */
    fun canRender(issues: List<ReelIssue>): Boolean = issues.none { it.blocking }

    fun canRender(project: ReelProject): Boolean = canRender(validate(project))

    private fun checkMotion(motion: ReelMotion, index: Int, issues: MutableList<ReelIssue>) {
        when (motion) {
            ReelMotion.None -> Unit
            is ReelMotion.ZoomIn -> if (!motion.focus.isInside) issues += ReelIssue(ReelIssueKind.MOTION_FOCUS, index)
            is ReelMotion.ZoomOut -> if (!motion.focus.isInside) issues += ReelIssue(ReelIssueKind.MOTION_FOCUS, index)
            is ReelMotion.Pan -> if (!motion.start.isUsable || !motion.end.isUsable) {
                issues += ReelIssue(ReelIssueKind.MOTION_RECT, index)
            }
        }
    }
}
