package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaId

data class GallerySelection(
    val selectedIds: Set<MediaId> = emptySet(),
) {
    val isActive: Boolean
        get() = selectedIds.isNotEmpty()

    val count: Int
        get() = selectedIds.size

    fun toggle(id: MediaId): GallerySelection {
        val updated = selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        return copy(selectedIds = updated)
    }

    fun selectAll(ids: Collection<MediaId>): GallerySelection =
        copy(selectedIds = ids.toSet())

    fun retainAvailable(ids: Set<MediaId>): GallerySelection =
        copy(selectedIds = selectedIds.intersect(ids))

    fun clear(): GallerySelection = GallerySelection()
}
