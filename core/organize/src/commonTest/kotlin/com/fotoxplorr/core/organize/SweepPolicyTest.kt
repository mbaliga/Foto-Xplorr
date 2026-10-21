package com.fotoxplorr.core.organize

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SweepPolicyTest {
    @Test
    fun `partial access refuses a sweep even when user requested`() {
        assertFalse(
            SweepPolicy.shouldSweep(
                catalogueSize = 100,
                missingCount = 1,
                userRequested = true,
                partialAccess = true,
            ),
        )
    }

    @Test
    fun `partial access refuses a sweep with zero missing`() {
        assertFalse(
            SweepPolicy.shouldSweep(
                catalogueSize = 100,
                missingCount = 0,
                userRequested = false,
                partialAccess = true,
            ),
        )
    }

    @Test
    fun `a user-requested refresh always sweeps outside partial access`() {
        assertTrue(
            SweepPolicy.shouldSweep(
                catalogueSize = 100,
                missingCount = 99,
                userRequested = true,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `an unattended sweep is allowed within the floor even on a tiny catalogue`() {
        assertTrue(
            SweepPolicy.shouldSweep(
                catalogueSize = 10,
                missingCount = 10,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `an unattended sweep is allowed up to a quarter of a large catalogue`() {
        assertTrue(
            SweepPolicy.shouldSweep(
                catalogueSize = 21_526,
                missingCount = 5_381,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `an unattended sweep is refused past both the floor and the quarter`() {
        assertFalse(
            SweepPolicy.shouldSweep(
                catalogueSize = 21_526,
                missingCount = 5_382,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `an unattended sweep of exactly 200 missing is allowed on a small catalogue`() {
        // 200 is the floor, not the quarter -- a catalogue of 400 would only allow 100 by the
        // quarter rule, so the floor is what actually permits this.
        assertTrue(
            SweepPolicy.shouldSweep(
                catalogueSize = 400,
                missingCount = 200,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `an unattended sweep of 201 missing on a small catalogue is refused`() {
        assertFalse(
            SweepPolicy.shouldSweep(
                catalogueSize = 400,
                missingCount = 201,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }

    @Test
    fun `zero missing is always allowed unattended`() {
        assertTrue(
            SweepPolicy.shouldSweep(
                catalogueSize = 0,
                missingCount = 0,
                userRequested = false,
                partialAccess = false,
            ),
        )
    }
}
