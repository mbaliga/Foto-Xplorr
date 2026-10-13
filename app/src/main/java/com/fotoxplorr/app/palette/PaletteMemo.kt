package com.fotoxplorr.app.palette

import android.util.LruCache
import com.fotoxplorr.core.model.MediaId

/**
 * The palettes this process has already computed, by photo.
 *
 * A palette is decoded on demand (a 100 px decode, see `readImagePalette`) and nothing persists it
 * yet -- a later task does. Until then this is the one place a palette outlives the row that
 * computed it, so the expanded map can colour a photo's dot with its own dominant colour *when
 * that colour is already known* and fall back to the accent otherwise (owner's rule), rather than
 * decoding every located photo in the library to draw a map of dots.
 *
 * Bounded, because a process that has opened ten thousand photos should not hold ten thousand
 * swatch lists; the memo is a convenience, not a store.
 */
object PaletteMemo {

    private val cache = LruCache<Long, List<PaletteSwatch>>(MAX_ENTRIES)

    fun get(id: MediaId): List<PaletteSwatch>? = synchronized(cache) { cache.get(id.value) }

    fun put(id: MediaId, swatches: List<PaletteSwatch>) {
        if (swatches.isEmpty()) return
        synchronized(cache) { cache.put(id.value, swatches) }
    }

    /** The most-represented colour of [id]'s palette as ARGB, or null when it has never been read. */
    fun dominantArgb(id: MediaId): Int? = get(id)?.maxByOrNull { it.proportion }?.argb

    /** Every memoised dominant colour at once, for a map that colours many dots in one pass. */
    fun dominantColours(): Map<MediaId, Int> = synchronized(cache) {
        cache.snapshot().mapNotNull { (id, swatches) ->
            swatches.maxByOrNull { it.proportion }?.let { MediaId(id) to it.argb }
        }.toMap()
    }

    /** Test seam: forget everything, so one test's palettes cannot colour another's map. */
    fun clear() = synchronized(cache) { cache.evictAll() }

    private const val MAX_ENTRIES = 512
}
