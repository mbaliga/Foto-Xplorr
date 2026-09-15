package com.fotoxplorr.app.pro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Today's only [ProEntitlement]: a boolean in this app's own private `SharedPreferences`, on this
 * device, with no receipt and nothing behind it that could survive a reinstall or follow the user
 * to a second device.
 *
 * That is a real limitation, not an oversight — see [ProEntitlement]'s KDoc for why a genuine
 * purchase has to live behind a separate `connect`-flavour implementation instead, and why
 * `offline` cannot host one even in principle (no `INTERNET` permission, and a Gradle classpath
 * gate that fails the build outright if Play Billing or any network library ever resolves onto
 * its runtime classpath). Until that `connect`-flavour class exists, this is what BOTH flavours
 * use, which is exactly why it takes no dependency beyond `SharedPreferences` and needs none.
 *
 * Follows the same shape as [com.fotoxplorr.app.favorites.FavoriteStore] and
 * [com.fotoxplorr.app.gallery.GalleryPreferences]: load once into a [MutableStateFlow] in the
 * constructor, then keep that flow and storage in lock-step on every write, so [isPro] is always
 * answered from memory and reading it never blocks a composition on disk I/O.
 *
 * The actual "can this only go from false to true, and is a repeat unlock a no-op" rule is not
 * here — see [ProEntitlementState], which carries it in pure Kotlin so it can be pinned by a
 * plain JVM test rather than only by prodding a switch on a device.
 */
class LocalProEntitlement(context: Context) : ProEntitlement {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val core = ProEntitlementState(preferences.getBoolean(KEY_IS_PRO, false))
    private val state = MutableStateFlow(core.isPro)

    override val isPro: StateFlow<Boolean> = state.asStateFlow()

    override fun recordUnlock() {
        core.recordUnlock()
        state.value = core.isPro
        // Written through unconditionally rather than only on the false -> true edge: a write
        // that only fires on a real change would be indistinguishable, from the outside, from a
        // write that silently failed the one time it mattered -- and this is a flag most users
        // flip at most once, so there is no hot path here worth guarding with a dirty check.
        preferences.edit().putBoolean(KEY_IS_PRO, core.isPro).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_pro"
        const val KEY_IS_PRO = "is_pro"
    }
}
