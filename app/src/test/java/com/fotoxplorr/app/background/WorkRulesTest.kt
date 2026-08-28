package com.fotoxplorr.app.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [formatHourOfDay], [WorkRules.hasActiveHoursWindow] and [summarize] -- the settings screen's
 *  plain-language layer on top of [WorkRules], pinned independently of [WorkRuleEvaluator] so a
 *  wording change cannot silently also change what is being evaluated, or vice versa. */
class WorkRulesTest {

    // ---- formatHourOfDay ----

    @Test
    fun `hours are zero-padded to a 24-hour clock label`() {
        assertEquals("00:00", formatHourOfDay(0))
        assertEquals("06:00", formatHourOfDay(6))
        assertEquals("22:00", formatHourOfDay(22))
        assertEquals("23:00", formatHourOfDay(23))
    }

    @Test
    fun `an out-of-range hour is clamped rather than producing a nonsense label`() {
        assertEquals("00:00", formatHourOfDay(-5))
        assertEquals("23:00", formatHourOfDay(99))
    }

    // ---- hasActiveHoursWindow ----

    @Test
    fun `equal start and end has no window`() {
        assertFalse(WorkRules(activeHoursStart = 9, activeHoursEnd = 9).hasActiveHoursWindow)
    }

    @Test
    fun `the shipped default (0 to 23) has no window either`() {
        assertFalse(WorkRules().hasActiveHoursWindow)
        assertFalse(WorkRules(activeHoursStart = 0, activeHoursEnd = 23).hasActiveHoursWindow)
    }

    @Test
    fun `a real overnight or daytime window is reported as a window`() {
        assertTrue(WorkRules(activeHoursStart = 22, activeHoursEnd = 6).hasActiveHoursWindow)
        assertTrue(WorkRules(activeHoursStart = 9, activeHoursEnd = 17).hasActiveHoursWindow)
        // Close to the full day but not quite it -- still a real, meaningful restriction.
        assertTrue(WorkRules(activeHoursStart = 0, activeHoursEnd = 22).hasActiveHoursWindow)
        assertTrue(WorkRules(activeHoursStart = 1, activeHoursEnd = 23).hasActiveHoursWindow)
    }

    // ---- summarize ----

    @Test
    fun `disabled rules summarize as unrestricted`() {
        val summary = summarize(WorkRules(enabled = false, requireIdle = true, minBatteryPercent = 90))
        assertTrue(summary.contains("off"))
        assertFalse("must not describe rules that are not actually being applied", summary.contains("90%"))
    }

    @Test
    fun `no active conditions summarizes as anytime`() {
        val summary = summarize(WorkRules(enabled = true, minBatteryPercent = 0))
        assertEquals("Indexing runs anytime, with no conditions.", summary)
    }

    @Test
    fun `the owner's own example sentence is what idle plus hours plus battery produces`() {
        val rules = WorkRules(
            requireIdle = true,
            activeHoursStart = 22,
            activeHoursEnd = 6,
            minBatteryPercent = 50,
        )
        val summary = summarize(rules)
        assertTrue(summary.contains("when the phone is idle"))
        assertTrue(summary.contains("between 22:00 and 06:00"))
        assertTrue(summary.contains("with battery above 50%"))
        // Order matters for a readable sentence: idle, then hours, then battery.
        assertTrue(
            summary.indexOf("idle") < summary.indexOf("22:00") &&
                summary.indexOf("22:00") < summary.indexOf("50%"),
        )
    }

    @Test
    fun `a battery-only rule does not mention hours or idle at all`() {
        val summary = summarize(WorkRules(minBatteryPercent = 35))
        assertEquals("Indexing runs with battery above 35%.", summary)
    }

    @Test
    fun `only-on-unmetered is named as Wi-Fi in the summary`() {
        val summary = summarize(WorkRules(minBatteryPercent = 0, onlyOnUnmetered = true))
        assertEquals("Indexing runs on Wi-Fi.", summary)
    }
}
