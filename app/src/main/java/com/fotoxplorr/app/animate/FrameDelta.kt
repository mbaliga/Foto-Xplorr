package com.fotoxplorr.app.animate

/** A rectangle of a canvas, [x0, x1) by [y0, y1). */
internal class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val area: Int get() = width * height
}

/**
 * Finds, frame by frame, which pixels of a lossy (palette) animation have to be repainted.
 *
 * It remembers the SOURCE colour each canvas pixel was last painted from and calls a pixel
 * unchanged while the new source stays within [tolerance] of it in every colour channel. Comparing
 * to what was last painted, not to the previous frame, is what stops a slow fade from drifting:
 * a pixel is repainted the moment it has moved more than the tolerance from what the viewer sees,
 * however slowly it got there. Comparing sources, not palette colours, is what keeps dither noise
 * from looking like change. Alpha is ignored: this is used for the opaque case only.
 *
 * The first frame is always painted whole.
 */
internal class DeltaTracker(private val width: Int, private val height: Int, private val tolerance: Int) {
    private val painted = IntArray(width * height)
    private var primed = false

    /**
     * Fill [mask] (width x height, 1 = repaint) for [src] and return the bounding box of the ones,
     * or null when nothing needs repainting (the frame is a repeat of what is on screen).
     */
    fun step(src: IntArray, mask: ByteArray): PixelRect? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var o = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = src[o]
                val changed = !primed || differs(p, painted[o])
                if (changed) {
                    mask[o] = 1
                    painted[o] = p
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                } else {
                    mask[o] = 0
                }
                o++
            }
        }
        primed = true
        return if (maxX < 0) null else PixelRect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun differs(a: Int, b: Int): Boolean {
        if (a == b) return false
        if (tolerance == 0) return ((a xor b) and 0xFFFFFF) != 0
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return dr > tolerance || dr < -tolerance || dg > tolerance || dg < -tolerance || db > tolerance || db < -tolerance
    }
}

/** The bounding box of the pixels whose alpha is at least the GIF cutoff, with [mask] filled, or null when none are. */
internal fun opaqueBounds(src: IntArray, width: Int, height: Int, mask: ByteArray): PixelRect? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var o = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            if ((src[o] ushr 24) >= AnimSequence.ALPHA_CUTOFF) {
                mask[o] = 1
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            } else {
                mask[o] = 0
            }
            o++
        }
    }
    return if (maxX < 0) null else PixelRect(minX, minY, maxX + 1, maxY + 1)
}
