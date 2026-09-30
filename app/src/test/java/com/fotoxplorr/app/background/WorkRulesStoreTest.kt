package com.fotoxplorr.app.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorkRulesStore.load]'s one-time migration for Phase 1 owner decision 5 (24 Sep 2026):
 * `requireCharging` now defaults ON, but an install that already recorded an explicit choice for
 * THIS control -- either direction -- must keep exactly that choice, and an install that only
 * ever touched a different control must still pick up the new default for this one. Getting this
 * wrong in either direction is the whole risk: silently flipping someone's deliberate "off" back
 * on is as bad as leaving a genuinely fresh install on the old, less battery-friendly default.
 *
 * Robolectric rather than a pure-JVM fake, because the thing under test IS whether a key was ever
 * actually written to `SharedPreferences` (`contains`), which only a real preferences file can
 * answer honestly. Each test gets a fresh preferences file from Robolectric and builds its own
 * store instance(s) against it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkRulesStoreTest {

    @Test
    fun `a fresh install defaults requireCharging to true`() {
        val store = WorkRulesStore(RuntimeEnvironment.getApplication())
        assertTrue(store.observe().value.requireCharging)
    }

    @Test
    fun `explicitly turning charging off survives a fresh store instance`() {
        val context = RuntimeEnvironment.getApplication()
        WorkRulesStore(context).setRequireCharging(false)

        val reloaded = WorkRulesStore(context)
        assertFalse(
            "an explicit off must not be migrated back to the new default",
            reloaded.observe().value.requireCharging,
        )
    }

    @Test
    fun `explicitly turning charging on survives a fresh store instance`() {
        val context = RuntimeEnvironment.getApplication()
        // Starts true already (the new default), but setting it explicitly is what this test
        // means to pin: the value came from a real write, not from having never been touched.
        WorkRulesStore(context).setRequireCharging(true)

        val reloaded = WorkRulesStore(context)
        assertTrue(reloaded.observe().value.requireCharging)
    }

    @Test
    fun `touching an unrelated rule does not count as editing the charging rule`() {
        val context = RuntimeEnvironment.getApplication()
        WorkRulesStore(context).setActiveHoursStart(22)

        val reloaded = WorkRulesStore(context)
        assertTrue(
            "requireCharging was never itself touched, so it must still pick up the new default",
            reloaded.observe().value.requireCharging,
        )
        assertTrue(
            "the unrelated edit must still have taken effect",
            reloaded.observe().value.activeHoursStart == 22,
        )
    }
}
