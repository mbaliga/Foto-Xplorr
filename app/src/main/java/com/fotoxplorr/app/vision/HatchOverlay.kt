package com.fotoxplorr.app.vision

/** The pattern layer for a picture: [argb] is [width] x [height], transparent except where a stroke goes. */
internal class HatchLayer(val width: Int, val height: Int, val argb: IntArray)

/**
 * Turns a [HatchPlan] into pixels to lay over the photo. The plan is made on a small colour-index
 * picture ([ColourIndex]); this draws it at a size near the screen, taking each output pixel's
 * cluster from the nearest index pixel, so a region's edge is as coarse as the index (a few output
 * pixels a step) and a stroke is as sharp as the output.
 *
 * A stroke is black over a light cluster and white over a dark one (by the cluster's own lightness),
 * mostly opaque, so it reads on both. Pure.
 */
internal object HatchOverlay {
    /** The long edge the layer is drawn at. */
    const val LONG_EDGE = 1024

    /** Output pixels between repeats of a pattern. */
    const val PERIOD = HatchPattern.DEFAULT_PERIOD

    private const val INK_ALPHA = 0xD0
    private const val LIGHT_CLUSTER = 55.0

    fun render(
        image: ColourIndexImage,
        plan: HatchPlan,
        longEdge: Int = LONG_EDGE,
        period: Int = PERIOD,
    ): HatchLayer {
        if (image.width <= 0 || image.height <= 0 || plan.isEmpty) return HatchLayer(0, 0, IntArray(0))
        val factor = longEdge.toDouble() / maxOf(image.width, image.height)
        val w = maxOf(1, Math.round(image.width * factor).toInt())
        val h = maxOf(1, Math.round(image.height * factor).toInt())
        val ink = IntArray(image.centres.size) { cluster ->
            val dark = ColourSpace.lab(image.centres[cluster]).l < LIGHT_CLUSTER
            (INK_ALPHA shl 24) or if (dark) 0x00FFFFFF else 0x00000000
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = minOf(image.height - 1, (y.toLong() * image.height / h).toInt())
            for (x in 0 until w) {
                val sx = minOf(image.width - 1, (x.toLong() * image.width / w).toInt())
                val cluster = image.index[sy * image.width + sx].toInt()
                val pattern = plan.patterns.getOrNull(cluster) ?: continue
                if (pattern.isInk(x, y, period)) out[y * w + x] = ink[cluster]
            }
        }
        return HatchLayer(w, h, out)
    }
}
