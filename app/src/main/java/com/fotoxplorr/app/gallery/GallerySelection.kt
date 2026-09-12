package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaId

/**
 * What is selected, and whether the user is selecting at all.
 *
 * [selecting] is separate from [selectedIds] being non-empty, which it did not used to be. Under
 * the old model the only way to be "in selection mode" was to already have something selected, so
 * the mode could only ever be entered by the same gesture that picked the first item — long press.
 * That gesture now holds a preview instead (owner, 2026-08-18: *"the quick preview must disappear
 * when the long press is released"*), and the way in is an explicit "Select photos" in the
 * gallery's actions room. Which means the app has to be able to represent *selecting, nothing
 * picked yet* — a state that was previously unrepresentable.
 */
data class GallerySelection(
    val selectedIds: Set<MediaId> = emptySet(),
    val selecting: Boolean = false,
) {
    /** Whether the selection chrome is up. True with nothing picked, once selecting has begun. */
    val isActive: Boolean
        get() = selecting || selectedIds.isNotEmpty()

    val count: Int
        get() = selectedIds.size

    /** Enter selection mode with nothing picked. */
    fun beginSelecting(): GallerySelection = copy(selecting = true)

    fun toggle(id: MediaId): GallerySelection {
        val updated = selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        // Stays in selection mode after the last item is removed, rather than dropping out from
        // under the user: they asked to select, and un-picking one thing is not "never mind".
        // Leaving is the X, which calls clear().
        return copy(selectedIds = updated, selecting = true)
    }

    fun selectAll(ids: Collection<MediaId>): GallerySelection =
        copy(selectedIds = ids.toSet(), selecting = true)

    fun retainAvailable(ids: Set<MediaId>): GallerySelection =
        copy(selectedIds = selectedIds.intersect(ids))

    /** Leave selection entirely: nothing picked, chrome gone. */
    fun clear(): GallerySelection = GallerySelection()
}
