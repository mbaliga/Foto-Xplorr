package com.fotoxplorr.app.vision

import kotlin.random.Random

/**
 * A picture cut down to at most [ColourIndex.MAX_K] colours.
 *
 * @property centres the colour of each cluster as packed opaque sRGB: the mean, in linear light, of
 *   the pixels assigned to it. Ordered darkest first, so a cluster id is stable for a given picture.
 * @property index per pixel (row-major, [width] by [height]) the id of its cluster, 0 until [centres] size.
 */
internal class ColourIndexImage(
    val centres: IntArray,
    val index: ByteArray,
    val width: Int,
    val height: Int,
)

/** The cluster colours of this picture, the most pixels first (ties by cluster number, so it is stable). */
internal fun ColourIndexImage.centresByShare(): List<Int> {
    val counts = IntArray(centres.size)
    for (b in index) {
        val id = b.toInt()
        if (id in counts.indices) counts[id]++
    }
    return centres.indices.sortedWith(compareBy({ -counts[it] }, { it })).map { centres[it] }
}

/**
 * Deterministic k-means in CIE Lab: k-means++ seeding from a seeded generator, then a fixed number of
 * Lloyd passes over an evenly strided sample of at most [SAMPLE] pixels, then every pixel goes to its
 * nearest centre. The same pixels, k and seed always give the same result.
 *
 * It is meant for a THUMBNAIL (a few hundred pixels a side, as the palette extractor uses): the
 * final pass converts every pixel to Lab, which is too slow for a full-size photo. Alpha is ignored,
 * so composite a transparent picture onto a background first. It is not a perceptual segmentation:
 * a smooth gradient is split into bands.
 */
internal object ColourIndex {
    const val MAX_K = 6
    const val SAMPLE = 4096
    private const val PASSES = 12

    fun quantize(argb: IntArray, width: Int, height: Int, k: Int, seed: Long = 1L): ColourIndexImage {
        require(width >= 0 && height >= 0 && argb.size >= width * height) { "pixel array too short" }
        val n = width * height
        if (n == 0) return ColourIndexImage(IntArray(0), ByteArray(0), width, height)
        val clusters = k.coerceIn(1, MAX_K)

        val lab = FloatArray(n * 3)
        val lin = FloatArray(n * 3)
        for (i in 0 until n) {
            val c = argb[i]
            val r = ColourSpace.decode8(ColourSpace.red(c))
            val g = ColourSpace.decode8(ColourSpace.green(c))
            val b = ColourSpace.decode8(ColourSpace.blue(c))
            lin[i * 3] = r.toFloat(); lin[i * 3 + 1] = g.toFloat(); lin[i * 3 + 2] = b.toFloat()
            val l = ColourSpace.labOfLinear(r, g, b)
            lab[i * 3] = l.l.toFloat(); lab[i * 3 + 1] = l.a.toFloat(); lab[i * 3 + 2] = l.b.toFloat()
        }

        val step = maxOf(1, n / SAMPLE)
        val sample = IntArray((n + step - 1) / step) { it * step }
        val rng = Random(seed)
        val centres = seedCentres(lab, sample, clusters, rng)

        val owner = IntArray(sample.size)
        for (pass in 0 until PASSES) {
            for (s in sample.indices) owner[s] = nearest(lab, sample[s], centres)
            if (!recompute(lab, sample, owner, centres)) break
        }

        val assignment = IntArray(n) { nearest(lab, it, centres) }

        // Mean colour of each cluster in linear light, then renumber darkest first.
        val sums = DoubleArray(clusters * 3)
        val counts = IntArray(clusters)
        for (i in 0 until n) {
            val c = assignment[i]
            counts[c]++
            for (ch in 0 until 3) sums[c * 3 + ch] += lin[i * 3 + ch].toDouble()
        }
        val used = (0 until clusters).filter { counts[it] > 0 }
        val colours = used.associateWith { c ->
            ColourSpace.pack(
                255,
                ColourSpace.encode8(sums[c * 3] / counts[c]),
                ColourSpace.encode8(sums[c * 3 + 1] / counts[c]),
                ColourSpace.encode8(sums[c * 3 + 2] / counts[c]),
            )
        }
        val order = used.sortedWith(compareBy({ ColourSpace.lab(colours.getValue(it)).l }, { it }))
        val newId = IntArray(clusters) { -1 }
        order.forEachIndexed { rank, old -> newId[old] = rank }
        return ColourIndexImage(
            centres = IntArray(order.size) { colours.getValue(order[it]) },
            index = ByteArray(n) { newId[assignment[it]].toByte() },
            width = width,
            height = height,
        )
    }

    private fun dist2(lab: FloatArray, i: Int, c: FloatArray, j: Int): Double {
        val dl = (lab[i * 3] - c[j * 3]).toDouble()
        val da = (lab[i * 3 + 1] - c[j * 3 + 1]).toDouble()
        val db = (lab[i * 3 + 2] - c[j * 3 + 2]).toDouble()
        return dl * dl + da * da + db * db
    }

    private fun nearest(lab: FloatArray, i: Int, centres: FloatArray): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (j in 0 until centres.size / 3) {
            val d = dist2(lab, i, centres, j)
            if (d < bestD) {
                bestD = d
                best = j
            }
        }
        return best
    }

    /** k-means++: the first centre at random, each next one with chance proportional to its squared distance. */
    private fun seedCentres(lab: FloatArray, sample: IntArray, k: Int, rng: Random): FloatArray {
        val centres = FloatArray(k * 3)
        fun set(slot: Int, pixel: Int) {
            for (ch in 0 until 3) centres[slot * 3 + ch] = lab[pixel * 3 + ch]
        }
        set(0, sample[rng.nextInt(sample.size)])
        val d2 = DoubleArray(sample.size) { dist2(lab, sample[it], centres, 0) }
        for (slot in 1 until k) {
            val total = d2.sum()
            val pick = if (total <= 0.0) {
                sample[rng.nextInt(sample.size)]
            } else {
                var target = rng.nextDouble() * total
                var chosen = sample.size - 1
                for (s in sample.indices) {
                    target -= d2[s]
                    if (target <= 0.0) {
                        chosen = s
                        break
                    }
                }
                sample[chosen]
            }
            set(slot, pick)
            for (s in sample.indices) d2[s] = minOf(d2[s], dist2(lab, sample[s], centres, slot))
        }
        return centres
    }

    /** Moves each centre to the mean of its sample; an empty one jumps to the sample furthest from its centre. Returns whether anything moved. */
    private fun recompute(lab: FloatArray, sample: IntArray, owner: IntArray, centres: FloatArray): Boolean {
        val k = centres.size / 3
        val sums = DoubleArray(k * 3)
        val counts = IntArray(k)
        for (s in sample.indices) {
            val c = owner[s]
            counts[c]++
            for (ch in 0 until 3) sums[c * 3 + ch] += lab[sample[s] * 3 + ch].toDouble()
        }
        var moved = false
        for (c in 0 until k) {
            val next = if (counts[c] > 0) {
                FloatArray(3) { (sums[c * 3 + it] / counts[c]).toFloat() }
            } else {
                var far = 0
                var farD = -1.0
                for (s in sample.indices) {
                    val d = dist2(lab, sample[s], centres, owner[s])
                    if (d > farD) {
                        farD = d
                        far = s
                    }
                }
                FloatArray(3) { lab[sample[far] * 3 + it] }
            }
            for (ch in 0 until 3) {
                if (centres[c * 3 + ch] != next[ch]) moved = true
                centres[c * 3 + ch] = next[ch]
            }
        }
        return moved
    }
}
