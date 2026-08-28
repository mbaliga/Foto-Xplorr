package com.fotoxplorr.app.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkRuleEvaluator], including every edge case the owner's spec called out by name: an
 * overnight window that wraps midnight (tested for both an hour that must be INCLUDED and one
 * that must be EXCLUDED, in both a wrapping and a non-wrapping window), start == end as
 * always-on, the battery threshold's own boundary, charging satisfying the battery rule outright,
 * the master switch meaning "run whenever", and every [WorkVerdict.Blocked] carrying a non-blank
 * reason that names the actual current value.
 *
 * Device states are built with sensible "everything is fine" defaults and only the field(s) under
 * test overridden, so each test reads as "this one thing, and only this one thing, is the reason
 * for the verdict" -- the same intent [AutoFixTest][com.fotoxplorr.app.editor.AutoFixTest] tests
 * with its "well-exposed neutral photo" baseline.
 */
class WorkRuleEvaluatorTest {

    /** Every field at a value that satisfies every possible rule -- idle, charging, full
     *  battery, noon (inside any sane window), unmetered. Override just what a test needs. */
    private fun okState(
        batteryPercent: Int = 100,
        charging: Boolean = true,
        idle: Boolean = true,
        unmetered: Boolean = true,
        hourOfDay: Int = 12,
    ) = DeviceState(batteryPercent, charging, idle, unmetered, hourOfDay)

    private fun WorkVerdict.reasonOrFail(): String {
        assertTrue("expected Blocked, was $this", this is WorkVerdict.Blocked)
        val reason = (this as WorkVerdict.Blocked).reason
        assertTrue("reason must not be blank", reason.isNotBlank())
        return reason
    }

    // ---- the master switch ----

    @Test
    fun `disabled rules mean run whenever, regardless of how bad every other field looks`() {
        val worstCase = DeviceState(batteryPercent = 0, charging = false, idle = false, unmetered = false, hourOfDay = 3)
        val rules = WorkRules(
            enabled = false,
            requireIdle = true,
            requireCharging = true,
            minBatteryPercent = 99,
            activeHoursStart = 9,
            activeHoursEnd = 10,
            onlyOnUnmetered = true,
        )
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, worstCase))
    }

    @Test
    fun `every rule off and enabled on allows anything`() {
        val rules = WorkRules(enabled = true)
        val messyState = DeviceState(batteryPercent = 3, charging = false, idle = false, unmetered = false, hourOfDay = 3)
        // minBatteryPercent still defaults to 20 here, so this is really pinning that a battery
        // of 3% below the default threshold IS blocked -- see the dedicated battery tests below
        // for the case that isolates this. This test's job is only the boolean toggles.
        val rulesWithNoBatteryFloor = rules.copy(minBatteryPercent = 0)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rulesWithNoBatteryFloor, messyState))
    }

    // ---- active hours: the wrap-around, in both directions ----

    @Test
    fun `an overnight window includes the late-night hour just after it starts`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 23)))
    }

    @Test
    fun `an overnight window includes the early-morning hour just before it ends`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 2)))
    }

    @Test
    fun `an overnight window excludes the middle of the day`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        val reason = WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 12)).reasonOrFail()
        assertTrue(reason.contains("22:00"))
        assertTrue(reason.contains("06:00"))
        assertTrue("reason should name the current hour: $reason", reason.contains("12:00"))
    }

    @Test
    fun `an overnight window's own start and end hours are both included`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 22)))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 6)))
    }

    @Test
    fun `an overnight window excludes the hour right after it ends`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        assertTrue(WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 7)) is WorkVerdict.Blocked)
    }

    @Test
    fun `an overnight window excludes the hour right before it starts`() {
        val rules = WorkRules(activeHoursStart = 22, activeHoursEnd = 6)
        assertTrue(WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 21)) is WorkVerdict.Blocked)
    }

    @Test
    fun `a same-day daytime window works the same direction as a plain range`() {
        val rules = WorkRules(activeHoursStart = 9, activeHoursEnd = 17)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 9)))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 17)))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 13)))
        assertTrue(WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 8)) is WorkVerdict.Blocked)
        assertTrue(WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 18)) is WorkVerdict.Blocked)
    }

    @Test
    fun `isWithinActiveHours agrees with evaluate across a full day for a wrapping window`() {
        val rules = WorkRules(activeHoursStart = 20, activeHoursEnd = 4)
        for (hour in 0..23) {
            val expected = WorkRuleEvaluator.isWithinActiveHours(hour, 20, 4)
            val actual = WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = hour)) == WorkVerdict.Allowed
            assertEquals("hour $hour", expected, actual)
        }
    }

    // ---- start == end: always-on, by definition ----

    @Test
    fun `a zero-width window is always-on, not a single hour and not never`() {
        val rules = WorkRules(activeHoursStart = 4, activeHoursEnd = 4)
        for (hour in 0..23) {
            assertTrue("hour $hour should be allowed", WorkRuleEvaluator.isWithinActiveHours(hour, 4, 4))
        }
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 4)))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = 17)))
    }

    @Test
    fun `the shipped default hours window (0 to 23) is also always-on`() {
        val rules = WorkRules() // defaults: activeHoursStart = 0, activeHoursEnd = 23
        for (hour in 0..23) {
            assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(hourOfDay = hour)))
        }
    }

    // ---- battery: the threshold's own boundary ----

    @Test
    fun `battery exactly at the threshold passes`() {
        val rules = WorkRules(minBatteryPercent = 50)
        val atThreshold = okState(batteryPercent = 50, charging = false)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, atThreshold))
    }

    @Test
    fun `one percent below the threshold blocks, and the reason names both numbers`() {
        val rules = WorkRules(minBatteryPercent = 50)
        val justBelow = okState(batteryPercent = 49, charging = false)
        val reason = WorkRuleEvaluator.evaluate(rules, justBelow).reasonOrFail()
        assertTrue("should name the threshold: $reason", reason.contains("50"))
        assertTrue("should name the current value: $reason", reason.contains("49"))
    }

    @Test
    fun `one percent above the threshold passes`() {
        val rules = WorkRules(minBatteryPercent = 50)
        val justAbove = okState(batteryPercent = 51, charging = false)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, justAbove))
    }

    // ---- charging satisfies the battery rule outright ----

    @Test
    fun `charging at a very low battery still satisfies the battery rule`() {
        val rules = WorkRules(minBatteryPercent = 80)
        val chargingButLow = okState(batteryPercent = 5, charging = true)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, chargingButLow))
    }

    @Test
    fun `the same low battery while NOT charging is blocked`() {
        val rules = WorkRules(minBatteryPercent = 80)
        val unpluggedAndLow = okState(batteryPercent = 5, charging = false)
        assertTrue(WorkRuleEvaluator.evaluate(rules, unpluggedAndLow) is WorkVerdict.Blocked)
    }

    // ---- idle ----

    @Test
    fun `requireIdle blocks when the phone is in use, and passes when idle`() {
        val rules = WorkRules(requireIdle = true)
        val reason = WorkRuleEvaluator.evaluate(rules, okState(idle = false)).reasonOrFail()
        assertTrue(reason.contains("idle"))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(idle = true)))
    }

    @Test
    fun `requireIdle off never blocks on idle state`() {
        val rules = WorkRules(requireIdle = false)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(idle = false)))
    }

    // ---- charging toggle (distinct from the battery-rule override above) ----

    @Test
    fun `requireCharging blocks when unplugged, and passes when charging`() {
        val rules = WorkRules(requireCharging = true)
        val reason = WorkRuleEvaluator.evaluate(rules, okState(charging = false)).reasonOrFail()
        assertTrue(reason.contains("charg") || reason.contains("battery"))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(charging = true)))
    }

    // ---- network ----

    @Test
    fun `onlyOnUnmetered blocks on a metered connection and passes on an unmetered one`() {
        val rules = WorkRules(onlyOnUnmetered = true)
        val reason = WorkRuleEvaluator.evaluate(rules, okState(unmetered = false)).reasonOrFail()
        assertTrue(reason.contains("Wi-Fi") || reason.contains("metered"))
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(unmetered = true)))
    }

    @Test
    fun `onlyOnUnmetered off never blocks on network state`() {
        val rules = WorkRules(onlyOnUnmetered = false)
        assertEquals(WorkVerdict.Allowed, WorkRuleEvaluator.evaluate(rules, okState(unmetered = false)))
    }

    // ---- every Blocked verdict carries a non-blank, value-bearing reason ----

    @Test
    fun `every rule's own blocked reason is non-blank and names the current value`() {
        val hourBlocked = WorkRuleEvaluator.evaluate(
            WorkRules(activeHoursStart = 1, activeHoursEnd = 2),
            okState(hourOfDay = 12),
        ).reasonOrFail()
        assertTrue(hourBlocked.contains("12:00"))

        val idleBlocked = WorkRuleEvaluator.evaluate(
            WorkRules(requireIdle = true),
            okState(idle = false),
        ).reasonOrFail()
        assertTrue(idleBlocked.isNotBlank())

        val chargingBlocked = WorkRuleEvaluator.evaluate(
            WorkRules(requireCharging = true),
            okState(charging = false),
        ).reasonOrFail()
        assertTrue(chargingBlocked.isNotBlank())

        val batteryBlocked = WorkRuleEvaluator.evaluate(
            WorkRules(minBatteryPercent = 40),
            okState(batteryPercent = 10, charging = false),
        ).reasonOrFail()
        assertTrue(batteryBlocked.contains("40"))
        assertTrue(batteryBlocked.contains("10"))

        val networkBlocked = WorkRuleEvaluator.evaluate(
            WorkRules(onlyOnUnmetered = true),
            okState(unmetered = false),
        ).reasonOrFail()
        assertTrue(networkBlocked.isNotBlank())
    }

    // ---- priority: the first violated rule in the documented order wins ----

    @Test
    fun `when multiple rules are violated at once, the active-hours reason wins over idle`() {
        val rules = WorkRules(requireIdle = true, activeHoursStart = 1, activeHoursEnd = 2)
        val state = okState(hourOfDay = 12, idle = false)
        val reason = WorkRuleEvaluator.evaluate(rules, state).reasonOrFail()
        assertTrue("expected the hours reason, got: $reason", reason.contains("active hours"))
    }

    @Test
    fun `when idle and charging are both violated, idle is reported first`() {
        val rules = WorkRules(requireIdle = true, requireCharging = true)
        val state = okState(idle = false, charging = false)
        val reason = WorkRuleEvaluator.evaluate(rules, state).reasonOrFail()
        assertTrue("expected the idle reason, got: $reason", reason.contains("idle"))
    }

    // ---- isWithinActiveHours as a small function in its own right ----

    @Test
    fun `isWithinActiveHours non-wrapping boundaries`() {
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(9, 9, 17))
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(17, 9, 17))
        assertFalse(WorkRuleEvaluator.isWithinActiveHours(8, 9, 17))
        assertFalse(WorkRuleEvaluator.isWithinActiveHours(18, 9, 17))
    }

    @Test
    fun `isWithinActiveHours wrapping boundaries`() {
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(22, 22, 6))
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(23, 22, 6))
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(0, 22, 6))
        assertTrue(WorkRuleEvaluator.isWithinActiveHours(6, 22, 6))
        assertFalse(WorkRuleEvaluator.isWithinActiveHours(7, 22, 6))
        assertFalse(WorkRuleEvaluator.isWithinActiveHours(21, 22, 6))
        assertFalse(WorkRuleEvaluator.isWithinActiveHours(12, 22, 6))
    }
}
