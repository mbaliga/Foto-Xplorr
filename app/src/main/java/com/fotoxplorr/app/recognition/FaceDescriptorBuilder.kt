package com.fotoxplorr.app.recognition

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 2D point in image pixel space. */
data class FacePoint(val x: Float, val y: Float)

/**
 * Builds the per-face descriptor that [FaceClustering] groups on. Pure maths -- no Android,
 * no ML Kit types -- so the whole thing is unit-testable.
 *
 * The descriptor concatenates two independently L2-normalised blocks:
 *
 *  1. **Shape** -- ML Kit's face-contour landmarks, similarity-normalised (translation,
 *     scale and in-plane rotation removed by aligning the eye axis to horizontal and
 *     scaling to unit inter-ocular distance). What survives is the geometry of the face:
 *     relative eye/nose/mouth/jaw placement.
 *  2. **Appearance** -- a small grid of mean intensities over the aligned crop, contrast
 *     normalised. This carries coarse tonal structure that pure geometry misses.
 *
 * ### Known limitation, stated plainly
 * This is *classical* face description (aligned geometry + coarse appearance), not a deep
 * face-recognition embedding trained with a metric loss. It groups the same person across
 * similar poses well, and degrades on large pose, expression and lighting changes -- where
 * it fails, it fails by **splitting one person into several groups**, not by merging two
 * people. That direction was chosen deliberately (see [FaceClustering]). The alternative,
 * a proper face-embedding network, would mean shipping or downloading a dedicated model;
 * this keeps People working offline, with zero setup, straight after install.
 */
object FaceDescriptorBuilder {

    /** Size of the appearance grid (APPEARANCE_GRID x APPEARANCE_GRID mean-intensity cells). */
    const val APPEARANCE_GRID = 8

    /** Appearance is the coarser signal, so it is weighted below shape. */
    const val APPEARANCE_WEIGHT = 0.6f

    /**
     * @param contour face-contour landmarks in image pixel space, in ML Kit's stable order.
     * @param leftEye/rightEye eye centres in the same space; used for alignment.
     * @param patch row-major grayscale samples in [0,1] of the aligned crop, length must be
     *   [APPEARANCE_GRID]^2 * k^2 for some integer k >= 1. Empty disables the appearance block.
     */
    fun build(
        contour: List<FacePoint>,
        leftEye: FacePoint?,
        rightEye: FacePoint?,
        patch: FloatArray = FloatArray(0),
    ): FloatArray {
        val shape = shapeBlock(contour, leftEye, rightEye)
        val appearance = appearanceBlock(patch)
        if (shape.isEmpty() && appearance.isEmpty()) return FloatArray(0)
        val out = FloatArray(shape.size + appearance.size)
        shape.copyInto(out, 0)
        for (index in appearance.indices) out[shape.size + index] = appearance[index] * APPEARANCE_WEIGHT
        return FaceClustering.normalized(out)
    }

    /**
     * Similarity-normalises [contour]: centred on the landmark centroid, rotated so the
     * eye axis is horizontal, scaled so the inter-ocular distance is 1. Returns a flat
     * [x0, y0, x1, y1, …], L2-normalised. Empty if there is nothing usable to normalise.
     */
    fun shapeBlock(
        contour: List<FacePoint>,
        leftEye: FacePoint?,
        rightEye: FacePoint?,
    ): FloatArray {
        if (contour.size < MIN_CONTOUR_POINTS) return FloatArray(0)

        val centroidX = contour.sumOf { it.x.toDouble() }.toFloat() / contour.size
        val centroidY = contour.sumOf { it.y.toDouble() }.toFloat() / contour.size

        // Prefer the eye axis for orientation/scale; fall back to landmark spread so a face
        // with undetected eyes still yields a (less stable) descriptor rather than none.
        val angle: Float
        val scale: Float
        if (leftEye != null && rightEye != null) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            val interocular = sqrt(dx * dx + dy * dy)
            if (interocular < MIN_INTEROCULAR_PX) return FloatArray(0)
            angle = -atan2(dy, dx)
            scale = 1f / interocular
        } else {
            angle = 0f
            val spread = sqrt(
                contour.sumOf { point ->
                    val ox = (point.x - centroidX).toDouble()
                    val oy = (point.y - centroidY).toDouble()
                    ox * ox + oy * oy
                }.toFloat() / contour.size,
            )
            if (spread <= 0f) return FloatArray(0)
            scale = 1f / spread
        }

        val cosine = cos(angle)
        val sine = sin(angle)
        val out = FloatArray(contour.size * 2)
        contour.forEachIndexed { index, point ->
            val ox = (point.x - centroidX) * scale
            val oy = (point.y - centroidY) * scale
            out[index * 2] = ox * cosine - oy * sine
            out[index * 2 + 1] = ox * sine + oy * cosine
        }
        return FaceClustering.normalized(out)
    }

    /**
     * Reduces [patch] to an [APPEARANCE_GRID]^2 grid of mean intensities, then removes the
     * mean and normalises -- so overall brightness and contrast do not drive the match.
     */
    fun appearanceBlock(patch: FloatArray): FloatArray {
        if (patch.isEmpty()) return FloatArray(0)
        val side = sqrt(patch.size.toFloat()).toInt()
        if (side <= 0 || side * side != patch.size || side % APPEARANCE_GRID != 0) return FloatArray(0)

        val block = side / APPEARANCE_GRID
        val cells = FloatArray(APPEARANCE_GRID * APPEARANCE_GRID)
        for (cellY in 0 until APPEARANCE_GRID) {
            for (cellX in 0 until APPEARANCE_GRID) {
                var sum = 0f
                for (y in 0 until block) {
                    val row = (cellY * block + y) * side + cellX * block
                    for (x in 0 until block) sum += patch[row + x]
                }
                cells[cellY * APPEARANCE_GRID + cellX] = sum / (block * block)
            }
        }
        val mean = cells.sum() / cells.size
        for (index in cells.indices) cells[index] -= mean
        if (cells.all { abs(it) < 1e-6f }) return FloatArray(0)
        return FaceClustering.normalized(cells)
    }

    private const val MIN_CONTOUR_POINTS = 8
    private const val MIN_INTEROCULAR_PX = 4f
}
