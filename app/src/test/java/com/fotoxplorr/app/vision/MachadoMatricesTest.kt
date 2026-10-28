package com.fotoxplorr.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulation matrices: transcription checked by the white-preserving property of every published
 * matrix, the full-dichromacy matrices spot-checked against the paper's severity 1.0 values, and the
 * interpolation rules.
 */
class MachadoMatricesTest {
    private val all = Deficiency.entries

    @Test
    fun `every published matrix keeps white and every grey - rows sum to one`() {
        for (d in all) for (step in 0..10) {
            val m = MachadoMatrices.atStep(d, step)
            for (row in 0 until 3) {
                val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
                assertEquals("$d step $step row $row", 1.0, sum, 2e-6)
            }
        }
    }

    @Test
    fun `severity zero is exactly the identity and the step zero table is too`() {
        for (d in all) {
            assertArrayEquals(Mat3.IDENTITY, MachadoMatrices.matrix(d, 0.0), 0.0)
            assertArrayEquals(Mat3.IDENTITY, MachadoMatrices.atStep(d, 0), 0.0)
        }
    }

    @Test
    fun `full dichromacy matrices are the paper's severity 1_0 values`() {
        assertArrayEquals(
            doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
            MachadoMatrices.matrix(Deficiency.PROTAN, 1.0), 1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
            MachadoMatrices.matrix(Deficiency.DEUTAN, 1.0), 1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
            MachadoMatrices.matrix(Deficiency.TRITAN, 1.0), 1e-9,
        )
    }

    @Test
    fun `a middle step is the paper's value`() {
        assertArrayEquals(
            doubleArrayOf(0.458064, 0.679578, -0.137642, 0.092785, 0.846313, 0.060902, -0.007494, -0.016807, 1.024301),
            MachadoMatrices.matrix(Deficiency.PROTAN, 0.5), 1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(0.547494, 0.607765, -0.155259, 0.181692, 0.781742, 0.036566, -0.010410, 0.027275, 0.983136),
            MachadoMatrices.matrix(Deficiency.DEUTAN, 0.5), 1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(1.017277, 0.027029, -0.044306, -0.006113, 0.958479, 0.047634, 0.006379, 0.248708, 0.744913),
            MachadoMatrices.matrix(Deficiency.TRITAN, 0.5), 1e-9,
        )
    }

    @Test
    fun `between two steps the matrix is the straight blend of its neighbours`() {
        for (d in all) {
            val lo = MachadoMatrices.atStep(d, 3)
            val hi = MachadoMatrices.atStep(d, 4)
            val mid = MachadoMatrices.matrix(d, 0.35)
            for (i in 0 until 9) assertEquals("$d [$i]", (lo[i] + hi[i]) / 2, mid[i], 1e-12)
            // and under the first step it blends from the identity
            val first = MachadoMatrices.matrix(d, 0.05)
            val one = MachadoMatrices.atStep(d, 1)
            for (i in 0 until 9) assertEquals("$d [$i]", (Mat3.IDENTITY[i] + one[i]) / 2, first[i], 1e-12)
        }
    }

    @Test
    fun `severity outside 0 to 1 or NaN is clamped`() {
        for (d in all) {
            assertArrayEquals(MachadoMatrices.matrix(d, 1.0), MachadoMatrices.matrix(d, 7.0), 0.0)
            assertArrayEquals(MachadoMatrices.matrix(d, 0.0), MachadoMatrices.matrix(d, -3.0), 0.0)
            assertArrayEquals(MachadoMatrices.matrix(d, 0.0), MachadoMatrices.matrix(d, Double.NaN), 0.0)
        }
    }

    @Test
    fun `the further the severity the further a protan or deutan matrix is from the identity`() {
        // Not asserted for tritan: its published steps 3 and 4 are, by the sum of absolute entries, a
        // hair out of order (0.69649 then 0.69509), so that measure is not monotone for it.
        for (d in listOf(Deficiency.PROTAN, Deficiency.DEUTAN)) {
            var last = -1.0
            for (step in 0..10) {
                val m = MachadoMatrices.atStep(d, step)
                val dist = (0 until 9).sumOf { kotlin.math.abs(m[it] - Mat3.IDENTITY[it]) }
                assertTrue("$d step $step: $dist after $last", dist >= last)
                last = dist
            }
        }
    }

    @Test
    fun `atStep hands out a copy`() {
        val a = MachadoMatrices.atStep(Deficiency.DEUTAN, 10)
        a[0] = 99.0
        assertEquals(0.367322, MachadoMatrices.atStep(Deficiency.DEUTAN, 10)[0], 1e-12)
    }
}
