package com.fotoxplorr.app.board

import com.fotoxplorr.app.palette.PaletteExtractor
import com.fotoxplorr.app.palette.PaletteSampling
import com.fotoxplorr.app.palette.PaletteSwatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The colours of the photos on a board, as an optional aid for picking a background or a colour block
 * that goes with them. Each photo is read through the board's photo source at the palette's own small
 * size ([PaletteSampling.DECODE_LIMITS], about 100 px), so a protected photo is refused by the host
 * like any other read, and the pixels of all of them are quantised together by [PaletteExtractor]: a
 * colour's share is its share of the photos taken as one, each photo counting for the same number of
 * pixels it was shrunk to. Nothing is stored; the strip is worked out when the person asks.
 */
object BoardPalette {
    const val MAX_PHOTOS = 24
    const val DEFAULT_COLOURS = 6

    /** Empty when no photo could be read. Call from a background dispatcher. */
    suspend fun of(source: BoardPhotoSource, refs: List<PhotoRef>, maxColours: Int = DEFAULT_COLOURS): List<PaletteSwatch> {
        val all = ArrayList<IntArray>()
        for (ref in refs.distinctBy { it.mediaId }.take(MAX_PHOTOS)) {
            currentCoroutineContext().ensureActive()
            val bitmap = source.decode(ref, PaletteSampling.DECODE_LIMITS) ?: continue
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) continue
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                all += pixels
            } finally {
                bitmap.recycle()
            }
        }
        if (all.isEmpty()) return emptyList()
        val joined = IntArray(all.sumOf { it.size })
        var at = 0
        for (part in all) {
            part.copyInto(joined, at)
            at += part.size
        }
        return PaletteExtractor.extract(joined, maxColours)
    }
}
