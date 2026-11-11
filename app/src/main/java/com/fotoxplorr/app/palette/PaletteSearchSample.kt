package com.fotoxplorr.app.palette

import com.fotoxplorr.core.search.PaletteSample

/**
 * This palette in the shape `:core:search` matches `colour:` terms against.
 *
 * Two shapes for one fact, on purpose: [PaletteSwatch] is what the details room draws and what the
 * store persists (packed ARGB plus a share, with a `hex` for the label), while [PaletteSample] is
 * what the matcher compares -- the same colours with their CIE Lab coordinates computed up front, in
 * flat arrays so the comparison loop allocates nothing. The conversion runs once per photo when the
 * recognition index is derived, off the main thread, and never per keystroke.
 */
fun List<PaletteSwatch>.toSearchSample(): PaletteSample {
    if (isEmpty()) return PaletteSample.EMPTY
    return PaletteSample.of(
        argb = IntArray(size) { this[it].argb },
        proportions = FloatArray(size) { this[it].proportion },
    )
}
