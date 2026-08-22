package com.fotoxplorr.app.pro

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether this install has unlocked Pro, and the one thing that can change it.
 *
 * This interface is the whole seam. Everything above it — the share sheet's locked watermark
 * switch ([com.fotoxplorr.app.share.ShareOptionsSheet]), the Settings "Pro" tab, and the render
 * decision in [com.fotoxplorr.app.share.SharePreparer] — asks [isPro] or calls [recordUnlock] and
 * never touches storage or a billing SDK directly. That is the same reason
 * [com.fotoxplorr.app.ai.RemoteAiBridge] exists: so a caller's answer and its actual behaviour
 * cannot disagree, and so what is behind the interface can be replaced later without hunting down
 * every call site that asks it a question.
 *
 * ## What is behind it today
 *
 * [LocalProEntitlement] — a flag in this app's own private `SharedPreferences`, on this device,
 * nothing else. There is no server, no receipt, no restore-purchases flow. "Unlocked" means
 * exactly "this app, on this device, remembers that [recordUnlock] was called," and that is the
 * whole truth of it — see [LocalProEntitlement]'s own KDoc, and keep any UI that calls
 * [recordUnlock] just as honest: it must never be reached from a control that claims money
 * changed hands, because with this implementation behind it, none has.
 *
 * ## What belongs behind it later, and where
 *
 * A real purchase — Google Play Billing, a receipt, a restore-purchases flow — is a
 * **`connect`-flavour concern only**. The `offline` flavour cannot host one even in principle,
 * and this is an architectural fact of this build, not a shortcut taken for this change:
 *
 *  - Its manifest (`src/offline/AndroidManifest.xml`) carries no `INTERNET` permission, and
 *    `app/build.gradle.kts`'s `VerifyOfflineManifestTask` fails the build if one ever merges in
 *    from a library AAR. Billing's purchase-acknowledgement round trip needs a socket the OS will
 *    simply refuse the offline flavour.
 *  - `VerifyOfflineClasspathTask` scans the offline flavour's *resolved* runtime classpath and
 *    fails the build if OkHttp, gRPC, Ktor or a short list of other network artifacts ever
 *    resolve onto it — declared or transitive. The Play Billing Library pulls in exactly that
 *    kind of stack. Depending on it here, even behind this interface, would fail that gate the
 *    moment `offline` tried to build.
 *
 * So a real implementation is added the same way [com.fotoxplorr.app.ai.ConnectivityBindings]'s
 * offline/connect split already works: a second class with this exact shape, at the same
 * fully-qualified name, added ONLY under `src/connect/java/com/fotoxplorr/app/pro/...`. The
 * compiler picks the implementation the variant's source set provides — no Hilt, no reflection,
 * no class-name loading — and the `offline` flavour never even sees that class exist, because its
 * compilation never includes that source set. Until that class is written, BOTH flavours fall
 * back to [LocalProEntitlement], which is exactly why this file and its implementation add no
 * dependency and need none: it is a boolean in `SharedPreferences`, on purpose, for now.
 */
interface ProEntitlement {

    /** True once this device has recorded a Pro unlock. Starts false on a fresh install. */
    val isPro: StateFlow<Boolean>

    /**
     * Record that this device now has Pro.
     *
     * TODO(billing): in the `connect`-flavour implementation, call this ONLY from a verified
     * purchase acknowledgement — a successful `PurchasesUpdatedListener` callback carrying the
     * Pro SKU, after `acknowledgePurchase` succeeds — never from the tap that merely opens the
     * billing sheet. Calling it eagerly, on the open rather than the acknowledged purchase, would
     * grant Pro to someone who looked at the price and then cancelled or failed to pay.
     *
     * Idempotent: calling this while already Pro changes nothing. A double-tap on "Unlock Pro",
     * or a purchase-update callback Play redelivers after a process restart, has to land the same
     * as calling it once — see [ProEntitlementState], which is where that rule actually lives and
     * is tested.
     */
    fun recordUnlock()
}
