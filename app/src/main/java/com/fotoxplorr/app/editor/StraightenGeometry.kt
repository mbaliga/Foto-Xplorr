package com.fotoxplorr.app.editor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The maths behind straighten's auto-crop: the largest axis-aligned rectangle, of a GIVEN aspect
 * ratio, that fits entirely inside a rectangle that has been rotated by a small angle.
 *
 * Free rotation used to be refused outright -- [EditRecipe.quarterTurns]'s old KDoc called it "a
 * crop problem" and stopped there. It is a crop problem, but a solvable one: rotating a WxH
 * rectangle about its own centre exposes four triangular gaps at the corners (there is no source
 * pixel there any more), and the only way to offer free rotation without either showing those gaps
 * or asking the user to crop by hand on every single adjustment is to compute, from the angle
 * alone, the biggest same-aspect rectangle that still lands entirely on real pixels -- then crop to
 * it automatically. That computation is what this file is.
 *
 * Pure Kotlin, no Android imports: this is arithmetic on four numbers, and the failure mode that
 * matters (a crop that clips into the exposed corner, or a crop the slider math cannot represent)
 * is exactly the kind of thing that must be pinned in a JVM test rather than eyeballed on a device.
 */
object StraightenGeometry {

    /** A rectangle's extent. Not `android.util.Size`, which is a framework class this module avoids. */
    data class Extent(val width: Float, val height: Float)

    /**
     * The largest WxH-aspect rectangle, centred on the same point, that fits inside a WxH rectangle
     * once THAT rectangle has been rotated by [angleRadians] about its own centre.
     *
     * ### The derivation
     * Place the source rectangle's centre at the origin, corners at (±W/2, ±H/2). A point `q` in
     * the OUTPUT (unrotated) frame is covered by the rotated source iff rotating it back by
     * `-angleRadians` lands it inside the original bounds. Checking that for the sought
     * rectangle's four corners -- sufficient because both shapes are convex, so a convex shape
     * whose corners all lie in another convex shape is itself entirely contained in it -- and using
     * `s = |sin(angleRadians)|`, `c = |cos(angleRadians)|` (the sign of the angle only mirrors which
     * corner binds, never how much) collapses to two linear constraints on the sought width `w` and
     * height `h`:
     *
     * ```
     * w*c + h*s <= W       (the rotated rectangle's horizontal extent)
     * w*s + h*c <= H       (its vertical extent)
     * ```
     *
     * Substituting the aspect constraint `h = w * H/W` turns each into a separate upper bound on
     * `w`; the tighter of the two is the answer. There is no extra "which corner is binding" branch
     * to get wrong, unlike the classic *unconstrained max-area* version of this problem (which
     * does need one) -- fixing the aspect ratio to the source's own is what removes it.
     *
     * @return an extent no larger than [sourceWidth] x [sourceHeight] in either dimension, and
     *   exactly that size when [angleRadians] is zero.
     */
    fun inscribedRect(sourceWidth: Float, sourceHeight: Float, angleRadians: Float): Extent {
        val w = sourceWidth.coerceAtLeast(0f)
        val h = sourceHeight.coerceAtLeast(0f)
        if (w <= 0f || h <= 0f) return Extent(0f, 0f)

        val s = abs(sin(angleRadians))
        val c = abs(cos(angleRadians))

        // Guarded rather than left to fall out of the formula: at s == 0 the formula below still
        // gives w back exactly (w*w / (w*1 + h*0) == w), but only if floating-point division
        // round-trips w/w to EXACTLY 1.0, which it always does for finite non-zero w -- this
        // early return exists so that fact does not have to be trusted. Persisted recipes rely on
        // straightenDegrees == 0f rendering as an EXACT identity; a rounding error of one part in
        // a million here would be invisible on screen and would still fail that promise.
        if (s < IDENTITY_EPSILON) return Extent(w, h)

        val byWidth = (w * w) / (w * c + h * s)
        val byHeight = (h * w) / (w * s + h * c)
        val outW = min(byWidth, min(byHeight, w))
        val outH = (outW * h / w).coerceIn(0f, h)
        return Extent(outW.coerceIn(0f, w), outH)
    }

    /** Below this, `sin` is close enough to zero that treating the angle as exactly 0 is correct. */
    private const val IDENTITY_EPSILON = 1e-7f
}
