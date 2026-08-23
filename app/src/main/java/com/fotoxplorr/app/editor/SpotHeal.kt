package com.fotoxplorr.app.editor

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One heal spot, in the FINAL rendered image's space: centre in normalised 0..1 of the output
 * width/height, radius as a fraction of the output's shorter side. Final space on purpose —
 * it makes the tap-to-spot mapping exact and the render insertion point trivial (heals run
 * last), at the stated cost that reframing after healing moves the spots with the frame, which
 * the editor's own Heal tool copy says out loud.
 */
data class HealSpot(val cx: Float, val cy: Float, val radius: Float) {
    init {
        require(cx in 0f..1f && cy in 0f..1f) { "Heal centre must be inside the image." }
        require(radius in MIN_RADIUS..MAX_RADIUS) { "Heal radius out of range." }
    }

    companion object {
        const val MIN_RADIUS = 0.005f
        const val MAX_RADIUS = 0.2f
        const val DEFAULT_RADIUS = 0.035f
    }
}

/**
 * Classic best-patch spot healing (editor-parity-plan P1's first half): for each spot, scan a
 * ring around it for the offset whose surrounding texture best matches the spot's own clean
 * context band, then clone that patch over the blemish with a feathered edge. This is the
 * time-honoured mechanism of every "spot heal" brush before the diffusion-model era: it
 * removes dust, blemishes, wires and birds against coherent texture, and it is fully
 * deterministic — same recipe, same pixels, every render.
 *
 * What it is NOT is generative inpainting: large occlusions against complex structure need the
 * model-pack route the parity plan describes (LaMa-class, via ASOM when it ships). This
 * implementation is the always-available floor, not the ceiling, and nothing here needs a
 * download, a model, or a byte of network.
 *
 * Everything is pure IntArray arithmetic so the whole tool is unit-testable on the JVM.
 */
object SpotHeal {

    /** Applies [spots] in order, mutating [pixels] (ARGB, row-major, [width]x[height]). */
    fun apply(pixels: IntArray, width: Int, height: Int, spots: List<HealSpot>) {
        require(pixels.size == width * height) { "Pixel buffer does not match its dimensions." }
        spots.forEach { spot -> healOne(pixels, width, height, spot) }
    }

    private fun healOne(pixels: IntArray, width: Int, height: Int, spot: HealSpot) {
        val shorter = min(width, height)
        val radius = max(2, (spot.radius * shorter).roundToInt())
        val cx = (spot.cx * width).roundToInt()
        val cy = (spot.cy * height).roundToInt()

        val offset = bestSourceOffset(pixels, width, height, cx, cy, radius) ?: return

        // Clone with a feathered edge: full replacement at the centre, easing to none at the
        // rim, so the patch has no visible cut line. Reads must come from a snapshot of the
        // ring source region BEFORE writes begin — a spot whose source ring overlaps the
        // write region must not smear its own half-written pixels.
        val snapshot = pixels.copyOf()
        val radiusSq = radius * radius
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y !in 0 until height) continue
            for (dx in -radius..radius) {
                val x = cx + dx
                if (x !in 0 until width) continue
                val distSq = dx * dx + dy * dy
                if (distSq > radiusSq) continue
                val sourceX = x + offset.first
                val sourceY = y + offset.second
                if (sourceX !in 0 until width || sourceY !in 0 until height) continue
                val dist = sqrt(distSq.toFloat()) / radius
                // Smoothstep on (1 - dist): 1 at centre, 0 at the rim.
                val t = (1f - dist).coerceIn(0f, 1f)
                val alpha = t * t * (3f - 2f * t)
                pixels[y * width + x] = blend(
                    over = snapshot[sourceY * width + sourceX],
                    under = snapshot[y * width + x],
                    alpha = alpha,
                )
            }
        }
    }

    /**
     * The donor search: candidate offsets on rings at 2.2r–3.6r, scored by how well the
     * candidate's CONTEXT BAND (the clean ring just outside the blemish, r..1.4r) matches the
     * spot's own. The band is what must agree for the clone to sit invisibly; the blemish
     * pixels themselves are exactly what must NOT vote.
     */
    private fun bestSourceOffset(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Pair<Int, Int>? {
        // The context band, sampled sparsely for speed: every ~30 degrees at two distances.
        val band = mutableListOf<Pair<Int, Int>>()
        for (ringMultiple in floatArrayOf(1.1f, 1.4f)) {
            val r = radius * ringMultiple
            var angle = 0.0
            while (angle < 2 * Math.PI) {
                val x = cx + (r * cos(angle)).roundToInt()
                val y = cy + (r * sin(angle)).roundToInt()
                if (x in 0 until width && y in 0 until height) band += x to y
                angle += Math.PI / 6
            }
        }
        if (band.size < 6) return null

        var best: Pair<Int, Int>? = null
        var bestScore = Long.MAX_VALUE
        for (ringMultiple in floatArrayOf(2.2f, 2.9f, 3.6f)) {
            val r = radius * ringMultiple
            var angle = 0.0
            while (angle < 2 * Math.PI) {
                val offsetX = (r * cos(angle)).roundToInt()
                val offsetY = (r * sin(angle)).roundToInt()
                angle += Math.PI / 8
                // The whole donor disc must be in bounds, or the clone would drag the edge in.
                if (cx + offsetX - radius < 0 || cx + offsetX + radius >= width ||
                    cy + offsetY - radius < 0 || cy + offsetY + radius >= height
                ) {
                    continue
                }
                var score = 0L
                var counted = 0
                for ((bx, by) in band) {
                    val sx = bx + offsetX
                    val sy = by + offsetY
                    if (sx !in 0 until width || sy !in 0 until height) continue
                    score += pixelDistance(pixels[by * width + bx], pixels[sy * width + sx])
                    counted += 1
                }
                if (counted >= band.size / 2 && score / counted < bestScore) {
                    bestScore = score / counted
                    best = offsetX to offsetY
                }
            }
        }
        return best
    }

    private fun pixelDistance(a: Int, b: Int): Long {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return (dr * dr + dg * dg + db * db).toLong()
    }

    private fun blend(over: Int, under: Int, alpha: Float): Int {
        val inverse = 1f - alpha
        val r = (((over shr 16) and 0xFF) * alpha + ((under shr 16) and 0xFF) * inverse).roundToInt()
        val g = (((over shr 8) and 0xFF) * alpha + ((under shr 8) and 0xFF) * inverse).roundToInt()
        val b = ((over and 0xFF) * alpha + (under and 0xFF) * inverse).roundToInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
