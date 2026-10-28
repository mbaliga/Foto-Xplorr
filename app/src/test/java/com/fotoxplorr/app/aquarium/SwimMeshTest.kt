package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SwimMeshTest {

    private val swim = SwimParams(waveHz = 1.5f, wavelengths = 1f, amplitude = 0.08f)
    private val out = FloatArray(SwimMesh.FLOATS)

    private fun pose(
        phase: Float = 0f,
        waveScale: Float = 1f,
        flipX: Float = 1f,
        pitch: Float = 0f,
        headRight: Boolean = true,
    ) = SwimPose().also {
        it.widthPx = 160f
        it.heightPx = 80f
        it.centerX = 300f
        it.centerY = 200f
        it.phase = phase
        it.waveScale = waveScale
        it.flipX = flipX
        it.pitch = pitch
        it.headRight = headRight
    }

    private fun x(i: Int, j: Int) = out[(j * (SwimMesh.COLS + 1) + i) * 2]
    private fun y(i: Int, j: Int) = out[(j * (SwimMesh.COLS + 1) + i) * 2 + 1]

    @Test
    fun `the vertex array has the size drawBitmapMesh wants`() {
        assertEquals((SwimMesh.COLS + 1) * (SwimMesh.ROWS + 1) * 2, SwimMesh.FLOATS)
    }

    @Test
    fun `with the wave off the mesh is the identity grid`() {
        SwimMesh.fill(out, pose(phase = 0f, waveScale = 0f), swim)
        for (j in 0..SwimMesh.ROWS) for (i in 0..SwimMesh.COLS) {
            assertEquals(300f + (i.toFloat() / SwimMesh.COLS - 0.5f) * 160f, x(i, j), 1e-3f)
            assertEquals(200f + (j.toFloat() / SwimMesh.ROWS - 0.5f) * 80f, y(i, j), 1e-3f)
        }
    }

    @Test
    fun `zero amplitude is the identity grid at any phase`() {
        SwimMesh.fill(out, pose(phase = 2.3f), swim.copy(amplitude = 0f))
        assertEquals(300f - 80f, x(0, 0), 1e-3f)
        assertEquals(200f + 40f, y(SwimMesh.COLS, SwimMesh.ROWS), 1e-3f)
    }

    @Test
    fun `the envelope grows from the head to the tail`() {
        var prev = -1f
        for (k in 0..20) {
            val u = k / 20f // tail to head
            val e = SwimMesh.envelope(u, swim.tailBias)
            if (prev >= 0f) assertTrue("envelope must not grow toward the head", e <= prev + 1e-6f)
            prev = e
        }
        assertEquals(1f, SwimMesh.envelope(0f, swim.tailBias), 1e-6f)
        assertTrue(SwimMesh.envelope(1f, swim.tailBias) <= SwimMesh.HEAD_FLOOR + 1e-6f)
    }

    @Test
    fun `the head column moves far less than the tail column`() {
        var headMax = 0f
        var tailMax = 0f
        for (k in 0 until 64) {
            SwimMesh.fill(out, pose(phase = k * 0.1f), swim)
            val mid = SwimMesh.ROWS / 2
            headMax = maxOf(headMax, abs(y(SwimMesh.COLS, mid) - 200f))
            tailMax = maxOf(tailMax, abs(y(0, mid) - 200f))
        }
        assertTrue("head $headMax tail $tailMax", headMax < tailMax * 0.3f)
        assertTrue(tailMax > 1f)
    }

    @Test
    fun `the wave is periodic in phase and travels along the body`() {
        val twoPi = (2 * Math.PI).toFloat()
        for (u in listOf(0f, 0.3f, 0.7f)) {
            assertEquals(SwimMesh.lateral(u, 1.1f, swim), SwimMesh.lateral(u, 1.1f + twoPi, swim), 1e-4f)
        }
        // Travelling, not standing: a crest at the tail end a moment ago is nearer the head now.
        val a = SwimMesh.lateral(0.2f, 0.5f, swim)
        val b = SwimMesh.lateral(0.6f, 0.5f, swim)
        assertTrue(abs(a - b) > 1e-3f)
    }

    @Test
    fun `the middle row keeps roughly its resting length`() {
        val rest = 160f
        for (k in 0 until 32) {
            SwimMesh.fill(out, pose(phase = k * 0.2f), swim)
            var len = 0f
            for (i in 0 until SwimMesh.COLS) {
                len += hypot(x(i + 1, 2) - x(i, 2), y(i + 1, 2) - y(i, 2))
            }
            assertEquals(rest, len, rest * 0.05f)
        }
    }

    @Test
    fun `columns stay in order so the sprite never folds back on itself`() {
        for (k in 0 until 32) {
            SwimMesh.fill(out, pose(phase = k * 0.2f), swim.copy(amplitude = 0.5f))
            for (i in 0 until SwimMesh.COLS) assertTrue(x(i + 1, 1) >= x(i, 1) - 1e-3f)
        }
    }

    @Test
    fun `facing left mirrors every vertex about the centre`() {
        SwimMesh.fill(out, pose(phase = 0.9f), swim)
        val right = out.copyOf()
        SwimMesh.fill(out, pose(phase = 0.9f, flipX = -1f), swim)
        for (v in 0 until SwimMesh.VERTEX_COUNT) {
            assertEquals(600f - right[v * 2], out[v * 2], 1e-3f)
            assertEquals(right[v * 2 + 1], out[v * 2 + 1], 1e-3f)
        }
    }

    @Test
    fun `mid turn the sprite collapses to a line without NaN`() {
        SwimMesh.fill(out, pose(flipX = 0f, waveScale = 0f), swim)
        for (v in 0 until SwimMesh.VERTEX_COUNT) assertEquals(300f, out[v * 2], 1e-3f)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `a head on the left drawing gets the wave from the other end`() {
        SwimMesh.fill(out, pose(phase = 1.3f, headRight = false), swim)
        val mid = SwimMesh.ROWS / 2
        // Head is the left column now, so it is the one that hardly moves.
        assertTrue(abs(y(0, mid) - 200f) < abs(y(SwimMesh.COLS, mid) - 200f) + 1e-3f)
        for (i in 0 until SwimMesh.COLS) assertTrue(x(i + 1, 1) >= x(i, 1) - 1e-3f)
    }

    @Test
    fun `pitch rotates about the centre`() {
        SwimMesh.fill(out, pose(waveScale = 0f, pitch = (Math.PI / 2).toFloat()), swim)
        // The right-middle vertex (head) ends up straight below the centre.
        assertEquals(300f, x(SwimMesh.COLS, SwimMesh.ROWS / 2), 1e-2f)
        assertEquals(200f + 80f, y(SwimMesh.COLS, SwimMesh.ROWS / 2), 1e-2f)
    }

    @Test
    fun `extreme or non finite input never produces a non finite vertex`() {
        val nasty = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -5f, 1e30f, -1e30f)
        for (a in nasty) {
            val p = pose()
            p.widthPx = a; p.heightPx = a; p.centerX = a; p.centerY = a
            p.phase = a; p.waveScale = a; p.flipX = a; p.pitch = a
            SwimMesh.fill(out, p, SwimParams(a, a, a, a))
            assertTrue("input $a", out.all { it.isFinite() })
        }
    }

    @Test
    fun `facing only flips on a sustained opposite velocity`() {
        assertEquals(1, SwimMesh.nextFacing(1, -0.001f, 0.2f)) // tiny opposite: no flip
        assertEquals(-1, SwimMesh.nextFacing(1, -0.1f, 0.2f))
        assertEquals(1, SwimMesh.nextFacing(1, 0.1f, 0.2f))
        assertEquals(-1, SwimMesh.nextFacing(-1, -0.1f, 0.2f))
        assertEquals(1, SwimMesh.nextFacing(-1, 0.1f, 0.2f))
        assertEquals(1, SwimMesh.nextFacing(1, Float.NaN, 0.2f))
    }

    @Test
    fun `the flip eases monotonically to the new side and stops there`() {
        var f = 1f
        var last = f
        var steps = 0
        while (f > -1f && steps < 1000) {
            f = SwimMesh.nextFlip(f, -1, 1f / 60f)
            assertTrue(f <= last)
            last = f
            steps++
        }
        assertEquals(-1f, f, 0f)
        assertTrue("took $steps steps", steps in 10..40)
        assertEquals(-1f, SwimMesh.nextFlip(-1f, -1, 0.05f), 0f)
        assertEquals(1f, SwimMesh.nextFlip(Float.NaN, 1, 0.05f), 0f)
    }

    @Test
    fun `pitch follows the climb and mirrors with the facing and is clamped`() {
        assertEquals(0f, SwimMesh.pitchFor(1, 0.1f, 0f), 1e-6f)
        assertTrue(SwimMesh.pitchFor(1, 0.1f, 0.05f) > 0f)
        assertTrue(SwimMesh.pitchFor(-1, -0.1f, 0.05f) < 0f)
        assertEquals(SwimMesh.MAX_PITCH, SwimMesh.pitchFor(1, 0.001f, 5f), 1e-6f)
        assertEquals(0f, SwimMesh.pitchFor(1, Float.NaN, Float.NaN), 0f)
    }
}
