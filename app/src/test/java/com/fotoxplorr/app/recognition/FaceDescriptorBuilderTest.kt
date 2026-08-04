package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FaceDescriptorBuilderTest {

    /** A crude but deterministic stand-in for a face contour: a ring plus two eye points. */
    private fun ring(count: Int = 16, radius: Float = 40f, cx: Float = 100f, cy: Float = 100f) =
        (0 until count).map { index ->
            val angle = (2.0 * Math.PI * index / count).toFloat()
            FacePoint(cx + radius * cos(angle), cy + radius * sin(angle))
        }

    private fun magnitude(vector: FloatArray): Float {
        var sum = 0f
        vector.forEach { sum += it * it }
        return sqrt(sum)
    }

    @Test
    fun `too few contour points yields no shape block`() {
        val shape = FaceDescriptorBuilder.shapeBlock(
            listOf(FacePoint(1f, 1f), FacePoint(2f, 2f)),
            FacePoint(0f, 0f),
            FacePoint(10f, 0f),
        )
        assertEquals(0, shape.size)
    }

    @Test
    fun `shape block is unit length`() {
        val shape = FaceDescriptorBuilder.shapeBlock(
            ring(), FacePoint(85f, 90f), FacePoint(115f, 90f),
        )
        assertEquals(32, shape.size)
        assertEquals(1f, magnitude(shape), 1e-5f)
    }

    @Test
    fun `shape block is invariant to translation`() {
        val a = FaceDescriptorBuilder.shapeBlock(
            ring(cx = 100f, cy = 100f), FacePoint(85f, 100f), FacePoint(115f, 100f),
        )
        val b = FaceDescriptorBuilder.shapeBlock(
            ring(cx = 400f, cy = 250f), FacePoint(385f, 250f), FacePoint(415f, 250f),
        )
        a.indices.forEach { assertEquals(a[it], b[it], 1e-4f) }
    }

    @Test
    fun `shape block is invariant to uniform scale`() {
        val small = FaceDescriptorBuilder.shapeBlock(
            ring(radius = 40f), FacePoint(85f, 100f), FacePoint(115f, 100f),
        )
        val large = FaceDescriptorBuilder.shapeBlock(
            ring(radius = 80f, cx = 200f, cy = 200f),
            FacePoint(170f, 200f),
            FacePoint(230f, 200f),
        )
        small.indices.forEach { assertEquals(small[it], large[it], 1e-4f) }
    }

    @Test
    fun `shape block is invariant to in-plane rotation`() {
        val angle = (Math.PI / 5).toFloat()
        fun rotate(point: FacePoint): FacePoint {
            val ox = point.x - 100f
            val oy = point.y - 100f
            return FacePoint(
                100f + ox * cos(angle) - oy * sin(angle),
                100f + ox * sin(angle) + oy * cos(angle),
            )
        }
        val upright = FaceDescriptorBuilder.shapeBlock(
            ring(), FacePoint(85f, 100f), FacePoint(115f, 100f),
        )
        val tilted = FaceDescriptorBuilder.shapeBlock(
            ring().map(::rotate), rotate(FacePoint(85f, 100f)), rotate(FacePoint(115f, 100f)),
        )
        // Rotation-normalisation is approximate at float precision; 1e-3 is well inside the
        // clustering threshold and far tighter than any real inter-person difference.
        upright.indices.forEach { assertEquals(upright[it], tilted[it], 1e-3f) }
    }

    @Test
    fun `missing eyes falls back to spread normalisation rather than failing`() {
        val shape = FaceDescriptorBuilder.shapeBlock(ring(), null, null)
        assertEquals(32, shape.size)
        assertEquals(1f, magnitude(shape), 1e-5f)
    }

    @Test
    fun `eyes too close together are rejected`() {
        val shape = FaceDescriptorBuilder.shapeBlock(
            ring(), FacePoint(100f, 100f), FacePoint(101f, 100f),
        )
        assertEquals(0, shape.size)
    }

    @Test
    fun `appearance block reduces a patch to the grid size`() {
        val side = FaceDescriptorBuilder.APPEARANCE_GRID * 4
        val patch = FloatArray(side * side) { (it % side) / side.toFloat() }
        val block = FaceDescriptorBuilder.appearanceBlock(patch)
        assertEquals(FaceDescriptorBuilder.APPEARANCE_GRID * FaceDescriptorBuilder.APPEARANCE_GRID, block.size)
        assertEquals(1f, magnitude(block), 1e-5f)
    }

    @Test
    fun `flat patches carry no appearance information`() {
        val side = FaceDescriptorBuilder.APPEARANCE_GRID * 2
        val patch = FloatArray(side * side) { 0.5f }
        assertEquals(0, FaceDescriptorBuilder.appearanceBlock(patch).size)
    }

    @Test
    fun `appearance block ignores overall brightness`() {
        val side = FaceDescriptorBuilder.APPEARANCE_GRID * 2
        val dark = FloatArray(side * side) { (it % side) / (side * 4f) }
        val bright = FloatArray(side * side) { dark[it] + 0.5f }
        val a = FaceDescriptorBuilder.appearanceBlock(dark)
        val b = FaceDescriptorBuilder.appearanceBlock(bright)
        a.indices.forEach { assertEquals(a[it], b[it], 1e-5f) }
    }

    @Test
    fun `malformed patch sizes are rejected rather than throwing`() {
        assertEquals(0, FaceDescriptorBuilder.appearanceBlock(FloatArray(0)).size)
        assertEquals(0, FaceDescriptorBuilder.appearanceBlock(FloatArray(7)).size)
        // Square, but not divisible by the grid.
        assertEquals(0, FaceDescriptorBuilder.appearanceBlock(FloatArray(9 * 9)).size)
    }

    @Test
    fun `build combines both blocks into one unit vector`() {
        val side = FaceDescriptorBuilder.APPEARANCE_GRID * 2
        val patch = FloatArray(side * side) { (it % side) / side.toFloat() }
        val vector = FaceDescriptorBuilder.build(
            ring(), FacePoint(85f, 100f), FacePoint(115f, 100f), patch,
        )
        val expected = 32 + FaceDescriptorBuilder.APPEARANCE_GRID * FaceDescriptorBuilder.APPEARANCE_GRID
        assertEquals(expected, vector.size)
        assertEquals(1f, magnitude(vector), 1e-5f)
    }

    @Test
    fun `build works with shape only`() {
        val vector = FaceDescriptorBuilder.build(ring(), FacePoint(85f, 100f), FacePoint(115f, 100f))
        assertEquals(32, vector.size)
        assertEquals(1f, magnitude(vector), 1e-5f)
    }

    @Test
    fun `build returns nothing when there is nothing to describe`() {
        assertEquals(0, FaceDescriptorBuilder.build(emptyList(), null, null).size)
    }

    @Test
    fun `same geometry at different scales stays inside the clustering threshold`() {
        val a = FaceDescriptorBuilder.build(
            ring(radius = 40f), FacePoint(85f, 100f), FacePoint(115f, 100f),
        )
        val b = FaceDescriptorBuilder.build(
            ring(radius = 90f, cx = 500f, cy = 300f),
            FacePoint(432f, 300f),
            FacePoint(568f, 300f),
        )
        val distance = FaceClustering.cosineDistance(a, b)
        assertTrue(
            "same face at another scale should cluster together, distance was $distance",
            distance < FaceClustering.DEFAULT_MAX_COSINE_DISTANCE,
        )
    }

    @Test
    fun `clearly different geometry exceeds the clustering threshold`() {
        val round = FaceDescriptorBuilder.build(
            ring(count = 16, radius = 40f), FacePoint(85f, 100f), FacePoint(115f, 100f),
        )
        // A tall, narrow ring: same landmark count, plainly different face proportions.
        val tall = FaceDescriptorBuilder.build(
            ring(count = 16, radius = 40f).map { FacePoint(it.x, 100f + (it.y - 100f) * 2.4f) },
            FacePoint(85f, 100f),
            FacePoint(115f, 100f),
        )
        val distance = FaceClustering.cosineDistance(round, tall)
        assertTrue(
            "different face geometry should not cluster together, distance was $distance",
            distance > FaceClustering.DEFAULT_MAX_COSINE_DISTANCE,
        )
    }

    @Test
    fun `vector encoding round-trips through the store blob format`() {
        val original = floatArrayOf(0.5f, -0.25f, 1f, 0f, 0.125f)
        val restored = decodeVector(encodeVector(original))
        assertEquals(original.size, restored.size)
        original.indices.forEach { assertTrue(abs(original[it] - restored[it]) < 1e-6f) }
        assertEquals(0, decodeVector(null).size)
        assertEquals(0, decodeVector(ByteArray(2)).size)
    }
}
