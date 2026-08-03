package com.fotoxplorr.app.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A deterministic, linear-memory embedding projection for large local libraries.
 *
 * It is deliberately named a similarity map rather than t-SNE: it uses signed random
 * projections plus LSH cluster separation, then a small grid-based collision relaxation.
 * Nearby items therefore reflect model similarity without claiming a canonical taxonomy.
 */
object SimilarityLayout {
    fun project(embeddings: List<StoredEmbedding>): List<SimilarityPoint> {
        if (embeddings.isEmpty()) return emptyList()

        val raw = embeddings.map { embedding ->
            val cluster = embedding.signature ushr CLUSTER_SHIFT
            val angle = (2.0 * PI * cluster / CLUSTER_COUNT).toFloat()
            val centreX = cos(angle) * CLUSTER_RING_RADIUS
            val centreY = sin(angle) * CLUSTER_RING_RADIUS
            val projectionX = signedProjection(embedding.vector, X_SEED)
            val projectionY = signedProjection(embedding.vector, Y_SEED)
            MutablePoint(
                mediaId = embedding.mediaId,
                cluster = cluster,
                x = centreX + projectionX * LOCAL_SPREAD,
                y = centreY + projectionY * LOCAL_SPREAD,
            )
        }.toMutableList()

        repeat(RELAXATION_PASSES) { relax(raw) }
        normalize(raw)
        return raw.map { SimilarityPoint(it.mediaId, it.x, it.y, it.cluster) }
    }

    private fun signedProjection(vector: ByteArray, seed: Int): Float {
        if (vector.isEmpty()) return 0f
        var dot = 0L
        var norm = 0L
        vector.forEachIndexed { index, byte ->
            val value = byte.toInt()
            val weight = if (mix(index, seed) and 1 == 0) 1 else -1
            dot += value.toLong() * weight
            norm += value.toLong() * value
        }
        if (norm == 0L) return 0f
        return (dot / sqrt(norm.toDouble() * vector.size)).toFloat().coerceIn(-1f, 1f)
    }

    private fun relax(points: MutableList<MutablePoint>) {
        val buckets = HashMap<Long, MutableList<MutablePoint>>(points.size / 3)
        points.forEach { point ->
            buckets.getOrPut(cellKey(point.x, point.y), ::mutableListOf).add(point)
        }
        buckets.values.forEach { bucket ->
            if (bucket.size < 2) return@forEach
            val centreX = bucket.map { it.x }.average().toFloat()
            val centreY = bucket.map { it.y }.average().toFloat()
            bucket.forEachIndexed { index, point ->
                var dx = point.x - centreX
                var dy = point.y - centreY
                if (dx == 0f && dy == 0f) {
                    val jitter = mix(point.mediaId.value.toInt(), index)
                    dx = if (jitter and 1 == 0) 1f else -1f
                    dy = if (jitter and 2 == 0) 1f else -1f
                }
                val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val pressure = ((bucket.size - 1).coerceAtMost(12) / 12f) * RELAXATION_STRENGTH
                point.x += dx / length * pressure
                point.y += dy / length * pressure
            }
        }
    }

    private fun normalize(points: MutableList<MutablePoint>) {
        val maxAbs = points.fold(0f) { current, point ->
            max(current, max(kotlin.math.abs(point.x), kotlin.math.abs(point.y)))
        }.coerceAtLeast(0.001f)
        points.forEach { point ->
            point.x = (point.x / maxAbs).coerceIn(-1f, 1f)
            point.y = (point.y / maxAbs).coerceIn(-1f, 1f)
        }
    }

    private fun cellKey(x: Float, y: Float): Long {
        val column = ((x + 2f) / CELL_SIZE).toInt()
        val row = ((y + 2f) / CELL_SIZE).toInt()
        return (column.toLong() shl 32) xor (row.toLong() and 0xffffffffL)
    }

    private fun mix(index: Int, seed: Int): Int {
        var value = index * -1640531527 + seed
        value = value xor (value ushr 16)
        value *= -1028477387
        return value xor (value ushr 13)
    }

    private data class MutablePoint(
        val mediaId: com.fotoxplorr.app.media.MediaId,
        val cluster: Int,
        var x: Float,
        var y: Float,
    )

    private const val CLUSTER_SHIFT = 20
    private const val CLUSTER_COUNT = 16
    private const val CLUSTER_RING_RADIUS = 0.58f
    private const val LOCAL_SPREAD = 0.46f
    private const val CELL_SIZE = 0.055f
    private const val RELAXATION_STRENGTH = 0.016f
    private const val RELAXATION_PASSES = 3
    private const val X_SEED = 0x4f1bbcdc
    private const val Y_SEED = 0x2c9277b5
}
