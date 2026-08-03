package com.fotoxplorr.app.ai

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.system.measureTimeMillis

class SimilarityEngineTest {
    @Test
    fun `quantized cosine preserves nearest direction`() {
        val anchor = EmbeddingRepository.quantize(floatArrayOf(1f, 0.2f, -0.1f, 0.7f))
        val near = EmbeddingRepository.quantize(floatArrayOf(0.98f, 0.18f, -0.12f, 0.72f))
        val far = EmbeddingRepository.quantize(floatArrayOf(-1f, -0.2f, 0.1f, -0.7f))

        assertTrue(EmbeddingRepository.cosine(anchor, near) > 0.98f)
        assertTrue(EmbeddingRepository.cosine(anchor, far) < -0.98f)
    }

    @Test
    fun `signature is deterministic and changes with embedding direction`() {
        val first = EmbeddingRepository.quantize(FloatArray(128) { index -> (index % 11 - 5) / 5f })
        val same = first.copyOf()
        val inverse = ByteArray(first.size) { index -> (-first[index]).toByte() }

        assertEquals(EmbeddingRepository.signature(first), EmbeddingRepository.signature(same))
        assertNotEquals(EmbeddingRepository.signature(first), EmbeddingRepository.signature(inverse))
    }

    @Test
    fun `twenty thousand embeddings produce bounded deterministic layout`() {
        val embeddings = (0 until 20_000).map { index ->
            val vector = ByteArray(96) { dimension ->
                val mixed = index * 31 + dimension * 17 + (index ushr 3)
                ((mixed % 255) - 127).toByte()
            }
            StoredEmbedding(
                mediaId = MediaId(index.toLong()),
                sourceRevision = index.toLong(),
                modelSha256 = "fixture-model",
                vector = vector,
                signature = EmbeddingRepository.signature(vector),
                x = null,
                y = null,
            )
        }

        lateinit var first: List<SimilarityPoint>
        val elapsed = measureTimeMillis { first = SimilarityLayout.project(embeddings) }
        val second = SimilarityLayout.project(embeddings)

        assertEquals(20_000, first.size)
        assertEquals(first, second)
        assertTrue(first.all { point -> point.x.isFinite() && point.y.isFinite() })
        assertTrue(first.all { point -> abs(point.x) <= 1f && abs(point.y) <= 1f })
        // This is intentionally generous: the assertion detects accidental quadratic regressions,
        // not ordinary CI variance.
        assertTrue("layout took ${elapsed}ms", elapsed < 30_000L)
    }
}
