package com.fotoxplorr.app.viewer.deepzoom

import com.fotoxplorr.app.media.OrientationOps

/**
 * Maps a rectangle in the UPRIGHT photo to the same pixels in the file's own (raw) grid, which is
 * the grid `BitmapRegionDecoder` reads in -- it applies no EXIF orientation (TRAPS #34 is the same
 * family: pixels are only right once orientation is accounted for). The forward transform the app
 * uses everywhere is "rotate clockwise, then flip horizontally" ([OrientationOps]); this undoes it:
 * the flip first, then the rotation.
 */
internal object RegionRects {

    /** The upright size for a raw size under [ops]. */
    fun uprightSize(rawW: Int, rawH: Int, ops: OrientationOps): Pair<Int, Int> =
        if (ops.swapsDimensions) rawH to rawW else rawW to rawH

    /** [r] (upright pixels) as a rectangle of the raw [rawW] x [rawH] grid. */
    fun toRaw(r: SourceRect, rawW: Int, rawH: Int, ops: OrientationOps): SourceRect {
        val (uw, _) = uprightSize(rawW, rawH, ops)
        // Undo the horizontal flip (applied last): x -> uw - x on the rectangle's edges.
        val l = if (ops.flipHorizontal) uw - r.right else r.left
        val rr = if (ops.flipHorizontal) uw - r.left else r.right
        val t = r.top
        val b = r.bottom
        // Undo the clockwise rotation. A point (x, y) of the raw grid lands at
        //   90:  (rawH - y, x)    180: (rawW - x, rawH - y)    270: (y, rawW - x)
        // so, inverting for the rotated-grid rectangle l..rr, t..b:
        return when (ops.rotationDegrees) {
            90 -> SourceRect(t, rawH - rr, b, rawH - l)
            180 -> SourceRect(rawW - rr, rawH - b, rawW - l, rawH - t)
            270 -> SourceRect(rawW - b, l, rawW - t, rr)
            else -> SourceRect(l, t, rr, b)
        }
    }
}
