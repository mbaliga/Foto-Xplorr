package com.fotoxplorr.app.wall

import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * What an enlarged print file is planned as: [PrintPlanner.plan] with `enlarge`, and when
 * [PrintPlanner.canEnlarge] says the offer should be made. Pure; the arithmetic is the part that
 * could overflow or run past the editor's 8192 px / 64 MP export limits.
 */
class EnlargePlanTest {

    @Test
    fun `a plan with enlarge asked reaches 300 dpi where a plain plan stops at the photo`() {
        val size = PrintSize(20.0, 30.0)
        val plain = PrintPlanner.plan(1200, 900, size)!!
        val enlarged = PrintPlanner.plan(1200, 900, size, enlarge = true)!!
        assertFalse(plain.enlarged)
        assertEquals(PrintLimit.PHOTO, plain.limitedBy)
        assertTrue(enlarged.enlarged)
        assertEquals(2362, enlarged.outW)
        assertEquals(3543, enlarged.outH)
        assertEquals(300, enlarged.dpi)
        assertEquals(PrintLimit.NONE, enlarged.limitedBy)
        // The part of the photo is the same.
        assertEquals(plain.crop, enlarged.crop)
    }

    @Test
    fun `asking to enlarge a photo that already has the pixels changes nothing`() {
        val size = PrintSize(10.0, 15.0)
        val plain = PrintPlanner.plan(2400, 1800, size)!!
        val asked = PrintPlanner.plan(2400, 1800, size, enlarge = true)!!
        assertEquals(plain.copy(enlarged = false), asked)
        assertFalse(asked.enlarged)
    }

    @Test
    fun `an enlargement stops at the size limits and the tag says what the pixels give`() {
        // 100 x 150 cm at 300 dpi is 11,811 x 17,717: far over. The long edge, 8192, rules.
        val plan = PrintPlanner.plan(1200, 900, PrintSize(100.0, 150.0), enlarge = true)!!
        assertTrue(plan.enlarged)
        assertEquals(PrintLimit.SIZE_LIMIT, plan.limitedBy)
        assertEquals(8192, plan.outH)
        assertTrue(plan.outW <= 8192)
        assertTrue(plan.dpi < 300)
        assertEquals(plan.outW / (100.0 / 2.54), plan.dpi.toDouble(), 1.0)
    }

    @Test
    fun `no size, however absurd, plans past the caps or overflows`() {
        val random = Random(11)
        repeat(2000) {
            val srcW = 1 + random.nextInt(20_000)
            val srcH = 1 + random.nextInt(20_000)
            val cmW = 0.5 + random.nextDouble() * 5000
            val cmH = 0.5 + random.nextDouble() * 5000
            val plan = PrintPlanner.plan(srcW, srcH, PrintSize(cmW, cmH), enlarge = true)!!
            assertTrue("edge ${plan.outW} x ${plan.outH}", plan.outW in 1..MAX_EXPORT_EDGE && plan.outH in 1..MAX_EXPORT_EDGE)
            assertTrue("pixels ${plan.outW} x ${plan.outH}", plan.outW.toLong() * plan.outH <= MAX_EXPORT_PIXELS)
            assertTrue(fitsEnlargeCap(plan.outW, plan.outH))
            assertTrue(plan.dpi in 1..JfifDensity.MAX_DPI)
            // The shape stays the frame's, to a pixel.
            val aspect = cmW / cmH
            assertTrue("shape ${plan.outW} x ${plan.outH} for $aspect", abs(plan.outW - plan.outH * aspect) <= max(1.0, aspect) + 1.0)
        }
        // And the extremes, which are where a Float or Int shortcut would fail.
        val big = PrintPlanner.plan(Int.MAX_VALUE, Int.MAX_VALUE, PrintSize(1e6, 1e6), enlarge = true)!!
        assertTrue(big.outW.toLong() * big.outH <= MAX_EXPORT_PIXELS)
    }

    @Test
    fun `enlarging is offered below the good band and not at or above it`() {
        // 20 x 30 cm from 1200 x 900: the middle 600 x 900, 76 dpi.
        assertTrue(PrintPlanner.canEnlarge(1200, 900, PrintSize(20.0, 30.0)))
        // 3000 px over 38 cm is 200.5 dpi, the good band: not offered; over 38.2 cm it is 199.5: offered.
        assertFalse(PrintPlanner.canEnlarge(3000, 3000, PrintSize(38.0, 38.0)))
        assertTrue(PrintPlanner.canEnlarge(3000, 3000, PrintSize(38.2, 38.2)))
        assertFalse(PrintPlanner.canEnlarge(4000, 3000, PrintSize(10.0, 15.0)))
        // Nothing sensible to say for nothing.
        assertFalse(PrintPlanner.canEnlarge(0, 900, PrintSize(20.0, 30.0)))
        assertFalse(PrintPlanner.canEnlarge(1200, 900, PrintSize(0.0, 30.0)))
    }
}
