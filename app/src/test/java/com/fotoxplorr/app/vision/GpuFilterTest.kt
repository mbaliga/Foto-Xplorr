package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 4x5 export: layout, identity, greys, and the measured error the GpuFilter KDoc quotes. */
class GpuFilterTest {

    @Test
    fun `layout is four rows of five with the matrix in the first three columns and a plain alpha row`() {
        val m = GpuFilter.simulate(Deficiency.DEUTAN, 1.0)
        assertEquals(20, m.size)
        val src = MachadoMatrices.matrix(Deficiency.DEUTAN, 1.0)
        for (r in 0 until 3) {
            for (c in 0 until 3) assertEquals(src[r * 3 + c].toFloat(), m[r * 5 + c], 0f)
            assertEquals(0f, m[r * 5 + 3], 0f)
            assertEquals(0f, m[r * 5 + 4], 0f)
        }
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.slice(15..19))
    }

    @Test
    fun `severity zero is the identity filter`() {
        for (d in Deficiency.entries) {
            val m = GpuFilter.simulate(d, 0.0)
            val identity = FloatArray(20).also { it[0] = 1f; it[6] = 1f; it[12] = 1f; it[18] = 1f }
            assertTrue("$d", identity.contentEquals(m))
            assertTrue("$d", identity.contentEquals(GpuFilter.daltonize(d, 0.0)))
        }
    }

    @Test
    fun `greys come through the filter unchanged`() {
        for (d in Deficiency.entries) for (matrix in listOf(GpuFilter.simulate(d), GpuFilter.daltonize(d))) {
            for (v in 0..255 step 5) {
                val out = GpuFilter.apply(matrix, argb(v, v, v))
                assertTrue("$d $v", VisionTestKit.maxChannelDiff(argb(v, v, v), out) <= 1)
            }
        }
    }

    @Test
    fun `apply keeps alpha and clamps`() {
        val out = GpuFilter.apply(GpuFilter.simulate(Deficiency.TRITAN), argb(255, 255, 0, 77))
        assertEquals(77, out ushr 24)
        val m = FloatArray(20).also { it[0] = 3f; it[18] = 1f }
        assertEquals(255, VisionTestKit.r(GpuFilter.apply(m, argb(200, 0, 0))))
    }

    @Test
    fun `measured error of the simulate filter - the figures the KDoc quotes`() {
        for (d in Deficiency.entries) {
            val exact = { c: Int -> Simulation.simulate(c, d, 1.0) }
            val all = GpuFilter.error(GpuFilter.simulate(d), exact)
            val muted = GpuFilter.error(GpuFilter.simulate(d), exact, mutedOnly = true)
            println("GPU-FILTER simulate $d all max=${all.maxSteps} mean=${"%.1f".format(all.meanSteps)} muted max=${muted.maxSteps} mean=${"%.1f".format(muted.meanSteps)}")
            assertTrue("$d all max ${all.maxSteps}", all.maxSteps in 75..95)
            assertTrue("$d all mean ${all.meanSteps}", all.meanSteps in 15.0..21.0)
            assertTrue("$d muted max ${muted.maxSteps}", muted.maxSteps in 20..38)
            assertTrue("$d muted mean ${muted.meanSteps}", muted.meanSteps in 1.5..4.5)
        }
    }

    @Test
    fun `measured error of the daltonize filter - far worse, as the KDoc says`() {
        for (d in Deficiency.entries) {
            val exact = { c: Int -> Daltonizer.daltonize(c, d, 1.0) }
            val all = GpuFilter.error(GpuFilter.daltonize(d), exact)
            val muted = GpuFilter.error(GpuFilter.daltonize(d), exact, mutedOnly = true)
            println("GPU-FILTER daltonize $d all max=${all.maxSteps} mean=${"%.1f".format(all.meanSteps)} muted max=${muted.maxSteps} mean=${"%.1f".format(muted.meanSteps)}")
            assertTrue("$d all max ${all.maxSteps}", all.maxSteps in 100..125)
            assertTrue("$d muted max ${muted.maxSteps}", muted.maxSteps in 80..110)
            assertTrue("$d muted mean ${muted.meanSteps}", muted.meanSteps in 7.0..13.0)
        }
    }

    @Test
    fun `error of an exact copy is zero`() {
        val identity = GpuFilter.simulate(Deficiency.PROTAN, 0.0)
        val e = GpuFilter.error(identity, { it })
        assertEquals(0, e.maxSteps)
        assertEquals(0.0, e.meanSteps, 0.0)
    }
}
