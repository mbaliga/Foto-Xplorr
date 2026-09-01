package com.fotoxplorr.app.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BackgroundWorkStatus.describe] -- the exact text the settings screen's "right now" line
 *  shows, pinned so a rewording is a deliberate test edit rather than an accident. */
class BackgroundWorkStatusTest {

    @Test
    fun `unknown reads as not checked yet, not as an error`() {
        assertEquals("Not checked yet.", BackgroundWorkStatus.Unknown.describe())
    }

    @Test
    fun `unrestricted says plainly that nothing is being held back`() {
        val text = BackgroundWorkStatus.Unrestricted.describe()
        assertTrue(text.contains("off"))
        assertTrue(text.contains("whenever the system schedules"))
    }

    @Test
    fun `pending and running are distinct, unambiguous states`() {
        assertEquals("Scheduled. Waiting for the system to give it a turn.", BackgroundWorkStatus.Pending.describe())
        assertEquals("Running now.", BackgroundWorkStatus.Running.describe())
    }

    @Test
    fun `blocked describes as exactly the evaluator's own reason, unaltered`() {
        val reason = "Waiting for battery above 50% (now 34%)"
        val status = BackgroundWorkStatus.Blocked(reason, checkedAt = 0L)
        // Verbatim, not merely "contains": describe() must never say work is running, or
        // anything else, when the underlying verdict was Blocked -- the reason IS the message.
        assertEquals(reason, status.describe())
    }

    @Test
    fun `blocked never describes itself as running`() {
        val status = BackgroundWorkStatus.Blocked("Waiting for the charger", checkedAt = 0L)
        assertTrue(!status.describe().contains("Running"))
    }
}
