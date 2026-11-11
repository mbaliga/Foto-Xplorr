package com.fotoxplorr.app.basemap

import java.util.concurrent.ConcurrentHashMap

/**
 * The basemap thinned per detail bucket, computed on first use and kept: land, lakes, borders.
 * Bucket 0 is the whole world in about a thousand pixels; past [MAX_BUCKET] the data is as fine as
 * it gets and the buckets share one result.
 */
class SimplifiedBasemap(val basemap: Basemap) {
    private val cache = ConcurrentHashMap<Int, Simplified>()

    class Simplified(val land: List<IntArray>, val lakes: List<IntArray>, val borders: List<IntArray>)

    fun at(bucket: Int): Simplified {
        val b = bucket.coerceIn(0, MAX_BUCKET)
        return cache.getOrPut(b) {
            val tol = Simplify.toleranceUnits(b)
            Simplified(
                land = basemap.land.paths.map { Simplify.simplify(it, tol) },
                lakes = basemap.lakes.paths.map { Simplify.simplify(it, tol) },
                borders = basemap.borders.paths.map { Simplify.simplify(it, tol) },
            )
        }
    }

    companion object {
        const val MAX_BUCKET = 6

        /** The detail bucket for a world drawn [worldPx] wide. */
        fun bucketFor(worldPx: Double): Int {
            val ratio = worldPx / Simplify.REFERENCE_WORLD_PX
            if (ratio <= 1.0) return 0
            return (Math.log(ratio) / Math.log(2.0)).toInt().coerceIn(0, MAX_BUCKET)
        }
    }
}
