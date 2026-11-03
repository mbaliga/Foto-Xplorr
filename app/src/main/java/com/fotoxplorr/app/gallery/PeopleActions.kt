package com.fotoxplorr.app.gallery

import com.fotoxplorr.core.model.MediaId

/**
 * What the People destination can ask the host to do to the stored people (names, hiding, merging,
 * "not this person"). All four write to the recognition store, on the device, and nothing else.
 *
 * Defaults do nothing, so a host that does not wire people (a test, a preview) still draws the pane.
 */
data class PeopleActions(
    val onRename: (personId: Long, name: String) -> Unit = { _, _ -> },
    val onHide: (personId: Long, hidden: Boolean) -> Unit = { _, _ -> },
    val onMerge: (sourceId: Long, targetId: Long) -> Unit = { _, _ -> },
    val onNotThisPerson: (personId: Long, mediaIds: Set<MediaId>) -> Unit = { _, _ -> },
)
