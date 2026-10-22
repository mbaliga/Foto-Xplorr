package com.fotoxplorr.app.spatial

import com.fotoxplorr.core.model.MediaId

/**
 * What the expanded map, opened from one photo's details room, is about: THIS photo, at THIS
 * place, among every other located photo drawn as a filled colour dot.
 *
 * One value shared by both flavours' map screens (`RichPhotoMapScreen` in connect,
 * `StampMapScreen` in offline), so the viewer can hand either the same description of what to
 * show without knowing which one compiles in -- the `PhotoMapPane` seam, extended.
 *
 * @param dotColors a photo's dominant palette colour (ARGB), for the photos whose palette has
 *   already been computed -- see [com.fotoxplorr.app.palette.PaletteMemo]. Any photo not in the
 *   map is drawn in [accentArgb]. The owner's rule: the palette colour when it is already known,
 *   the accent otherwise, never a decode per dot.
 */
data class MapFocus(
    val assetId: MediaId,
    val latitude: Double,
    val longitude: Double,
    val dotColors: Map<MediaId, Int> = emptyMap(),
    val accentArgb: Int,
) {
    /** The dot colour for [id]: its own palette colour when known, the accent otherwise. */
    fun colorFor(id: MediaId): Int = dotColors[id] ?: accentArgb
}
