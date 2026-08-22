package com.fotoxplorr.app.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule Pro depends on: an unlock can only ever turn [ProEntitlementState.isPro] on, and
 * recording one twice must behave like recording it once, not like a toggle.
 *
 * Kept separate from [LocalProEntitlement], which needs an Android `Context` to construct its
 * `SharedPreferences` and so cannot run on a plain JVM test without Robolectric -- see this
 * class's own KDoc. Everything worth pinning about the transition lives here instead.
 */
class ProEntitlementStateTest {

    @Test
    fun `a fresh install with nothing on record starts locked`() {
        assertFalse(ProEntitlementState(initiallyPro = false).isPro)
    }

    @Test
    fun `loading a previously-recorded unlock starts unlocked`() {
        // The constructor's job is to reproduce whatever storage already said, not to decide
        // anything itself -- a reinstall of LocalProEntitlement over existing SharedPreferences
        // must not silently re-lock a device that was already Pro.
        assertTrue(ProEntitlementState(initiallyPro = true).isPro)
    }

    @Test
    fun `recording an unlock turns Pro on`() {
        val state = ProEntitlementState(initiallyPro = false)
        state.recordUnlock()
        assertTrue(state.isPro)
    }

    @Test
    fun `recording an unlock twice is idempotent, not a toggle`() {
        // The regression this guards: if this were `isPro = !isPro` or similar, a double-tap on
        // "Unlock Pro" -- or Play redelivering a purchase-update callback after a process
        // restart, once real billing is wired in -- would silently re-lock a paying user.
        val state = ProEntitlementState(initiallyPro = false)
        state.recordUnlock()
        state.recordUnlock()
        assertTrue("a second unlock must not flip Pro back off", state.isPro)
    }

    @Test
    fun `recording an unlock on an already-unlocked state changes nothing`() {
        val state = ProEntitlementState(initiallyPro = true)
        state.recordUnlock()
        assertTrue(state.isPro)
    }
}
