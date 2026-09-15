package com.fotoxplorr.app.pro

/**
 * The entitlement's actual state transition, pulled out of [LocalProEntitlement] on purpose.
 *
 * [LocalProEntitlement] needs a `Context` to construct — `SharedPreferences` demands one — which
 * on this module's plain JVM unit tests means Robolectric or nothing (see the note on
 * `EditRecipe.toColorMatrix` in `EditRecipeTest` for a case in this codebase where that cost was
 * judged not worth paying). The one rule that actually matters for Pro does not need a `Context`
 * at all: an unlock can only ever turn it ON, and recording one twice must not act like a toggle.
 * Keeping that rule here, in pure Kotlin, means a plain JUnit test pins it in milliseconds instead
 * of it living as an assumption baked into [LocalProEntitlement]'s read-modify-write of a
 * `SharedPreferences` flag, untested until someone taps "Unlock Pro" twice on a device.
 *
 * @param initiallyPro whatever was already on record — loaded from storage, or false on a fresh
 *   install where nothing has been recorded yet.
 */
class ProEntitlementState(initiallyPro: Boolean) {

    var isPro: Boolean = initiallyPro
        private set

    /**
     * Record an unlock. Always leaves [isPro] true.
     *
     * Deliberately no argument, and no way to set [isPro] false from in here: the only two events
     * with an opinion about this flag are "an unlock was recorded" (this) and "load whatever
     * storage already said" (the constructor). A revoke — a refund, a failed purchase validation
     * — is a real need a paid Pro tier will eventually have, but nothing in this app implements
     * one yet, and adding a `fun lock()` nobody calls would be a lie about what the app can
     * currently do rather than a preparation for what it will do.
     */
    fun recordUnlock() {
        isPro = true
    }
}
