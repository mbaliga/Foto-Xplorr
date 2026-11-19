package com.fotoxplorr.app.receive

/**
 * Every word the share-receive sheet shows: one short sentence (or a one-word label) per string,
 * and no string reads as a promise the code does not keep.
 */
object ReceiveCopy {
    const val VIEW = "View"
    const val VIEW_HINT = "Look at them here without keeping a copy."
    const val SAVE = "Save to library"
    const val SAVE_HINT = "Keep a new copy of each in your library."
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val SAVE_REST = "Save the rest"
    const val OPEN_LIBRARY = "Open library"
    const val CHOICE_PROMPT = "What would you like to do with them?"
    const val NOTHING_USABLE = "None of these could be opened."
    const val PREPARING = "Checking what was shared."
    const val SAVING_A11Y = "Saving progress"
    const val CLOSE_VIEWER = "Close"
    const val VIEW_FAILED = "Could not open this one."
    const val ORIGINALS_UNTOUCHED = "The originals are not changed."

    fun title(plan: ReceivePlan): String {
        val n = plan.items.size
        return when {
            plan.videos == 0 -> if (n == 1) "1 photo was shared with you." else "$n photos were shared with you."
            plan.images == 0 -> if (n == 1) "1 video was shared with you." else "$n videos were shared with you."
            else -> "$n photos and videos were shared with you."
        }
    }

    /** The one extra sentence about what was left out, or null when everything made it. */
    fun leftOut(plan: ReceivePlan): String? = when {
        plan.wasCapped -> "Only the first ${ReceivePlan.MAX_ITEMS} are kept, the other ${plan.overCap} were left out."
        plan.unreadable + plan.notMedia == 1 -> "1 shared file could not be used."
        plan.unreadable + plan.notMedia > 1 -> "${plan.unreadable + plan.notMedia} shared files could not be used."
        else -> null
    }

    fun saving(done: Int, total: Int): String = "Saving $done of $total."

    fun summary(progress: ReceiveProgress, cancelled: Boolean): String {
        val saved = if (progress.saved == 1) "Saved 1 photo or video." else "Saved ${progress.saved} photos and videos."
        return when {
            cancelled && progress.remaining > 0 -> "Stopped after ${progress.saved} of ${progress.total}."
            progress.failed == 1 -> "$saved 1 could not be saved."
            progress.failed > 1 -> "$saved ${progress.failed} could not be saved."
            else -> saved
        }
    }

    fun position(position: Int, total: Int): String = "$position of $total"
}
