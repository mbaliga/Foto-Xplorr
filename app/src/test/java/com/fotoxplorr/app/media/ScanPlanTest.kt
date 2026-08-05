package com.fotoxplorr.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that stops a screenshot from restarting a 21,526-item index.
 *
 * These matter more than most tests because the failure mode is silent: a delta that skips a
 * photo produces a gallery that is simply missing it, with nothing to notice until someone
 * goes looking for that photo. So the boundaries are pinned exactly.
 */
class ScanPlanTest {

    @Test
    fun `first run has nothing indexed so it must be a full pass`() {
        assertEquals(
            ScanPlan.Full,
            ScanPlan.decide(lastCompletedSeconds = 0L, knownAssetCount = 0, userRequested = false),
        )
    }

    @Test
    fun `an empty repository forces a full pass even with a watermark`() {
        // The catalogue can be wiped by a schema upgrade while the watermark survives in
        // prefs. A delta here would leave the gallery permanently empty.
        assertEquals(
            ScanPlan.Full,
            ScanPlan.decide(lastCompletedSeconds = 1_700_000_000L, knownAssetCount = 0, userRequested = false),
        )
    }

    @Test
    fun `a missing or corrupt watermark forces a full pass`() {
        assertEquals(
            ScanPlan.Full,
            ScanPlan.decide(lastCompletedSeconds = 0L, knownAssetCount = 21_526, userRequested = false),
        )
        assertEquals(
            ScanPlan.Full,
            ScanPlan.decide(lastCompletedSeconds = -5L, knownAssetCount = 21_526, userRequested = false),
        )
    }

    @Test
    fun `a user-requested refresh is always full`() {
        // "Refresh" that quietly does almost nothing is worse than a slow refresh.
        assertEquals(
            ScanPlan.Full,
            ScanPlan.decide(lastCompletedSeconds = 1_700_000_000L, knownAssetCount = 21_526, userRequested = true),
        )
    }

    @Test
    fun `the ordinary case -- a screenshot on a populated library -- is a delta`() {
        val plan = ScanPlan.decide(
            lastCompletedSeconds = 1_700_000_000L,
            knownAssetCount = 21_526,
            userRequested = false,
        )
        assertTrue("expected a delta, got $plan", plan is ScanPlan.Delta)
    }

    @Test
    fun `the delta bound rewinds so a same-second write is never lost`() {
        // MediaStore DATE_MODIFIED is second-granular: a row written in the same second the
        // previous scan finished would be excluded by a strict bound and never seen again.
        val since = ScanPlan.deltaSince(1_700_000_000L)
        assertEquals(1_700_000_000L - ScanPlan.WATERMARK_REWIND_SECONDS, since)
        assertTrue("the bound must not exceed the watermark", since < 1_700_000_000L)
    }

    @Test
    fun `the rewound bound never goes negative`() {
        assertEquals(0L, ScanPlan.deltaSince(3L))
        assertEquals(0L, ScanPlan.deltaSince(0L))
    }
}
