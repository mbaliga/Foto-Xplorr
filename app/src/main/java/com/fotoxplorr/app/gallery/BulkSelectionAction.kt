package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaId

enum class BulkMarkAction {
    MARK,
    UNMARK,
}

fun bulkMarkAction(
    selectedIds: Set<MediaId>,
    currentlyMarkedIds: Set<MediaId>,
): BulkMarkAction = if (
    selectedIds.isNotEmpty() && selectedIds.all { it in currentlyMarkedIds }
) {
    BulkMarkAction.UNMARK
} else {
    BulkMarkAction.MARK
}
