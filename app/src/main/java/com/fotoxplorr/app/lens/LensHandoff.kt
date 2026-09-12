package com.fotoxplorr.app.lens

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * The two off-device hand-offs this card can offer, and the reason both are safe to fire from
 * EVERY flavor, including `offline`.
 *
 * Both build IMPLICIT intents: this app describes what it wants done and hands that description
 * to the system, which resolves it to whatever app the person has installed and launches THAT
 * app's own activity -- this process never opens a socket to do it. The offline flavor's
 * manifest already relies on exactly this distinction for the app's existing share sheet
 * (`android.permission.INTERNET` never appears in `src/offline/AndroidManifest.xml`): handing
 * data to another app through an intent is not this app reaching the network, it is another
 * app's own business once it has the data, under that other app's own permissions. See
 * [com.fotoxplorr.app.lens.TranslatorUnavailableException]'s callers for why the offline flavor
 * needs this at all -- it has no translator of its own to run.
 */
object LensHandoff {

    /**
     * An intent to send [text] to a translator app, preferring one that understands it is being
     * asked to TRANSLATE rather than merely to receive text.
     *
     * `ACTION_PROCESS_TEXT` is what Android's own text-selection toolbar sends to a "Translate"
     * entry -- it is what a translator app registers for to appear there, Google Translate
     * included -- so it is tried first. `ACTION_SEND` is the broader, near-universal fallback for
     * when nothing on the device answers the more specific one.
     *
     * KNOWN LIMITATION: the preference check below uses [Intent.resolveActivity], which on
     * Android 11+ can under-report matches for a package this app's manifest has not declared
     * visibility of via a `<queries>` element -- and this app's manifest does not currently
     * declare one for `ACTION_PROCESS_TEXT` or `ACTION_SEND`. In practice this only ever costs
     * the MORE SPECIFIC entry point: most translator apps (Google Translate included) also
     * register for plain `ACTION_SEND`, so on a device where the check under-reports, the
     * hand-off still reaches the same app through its general "Share" surface rather than its
     * dedicated translate one -- a real but minor degradation, not a broken feature. Fixing it
     * precisely needs a `<queries>` addition to `app/src/main/AndroidManifest.xml`, which is
     * outside this package's ownership this round; see this feature's own integration notes.
     */
    fun translateIntent(context: Context, text: String): Intent {
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val resolves = processText.resolveActivity(context.packageManager) != null
        return if (resolves) processText else sendIntent(text)
    }

    private fun sendIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    /**
     * A plain web search for [query] -- the card's own label on the control that fires this
     * says "leaves Foto Xplorr" before this is ever called, not after, per this feature's
     * requirement that sending text off-device is labelled plainly ahead of time rather than
     * discovered by the person mid-action.
     */
    fun webSearchIntent(query: String): Intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
        putExtra(SearchManager.QUERY, query)
    }

    /**
     * Wraps [intent] in a chooser titled [chooserTitle] (or fires it bare when null) and
     * launches it, returning whether anything actually started.
     *
     * `false`, not a crash: on some devices NOTHING resolves a given implicit intent -- a fresh
     * device with no browser configured, an offline device with no translator app installed --
     * and [ActivityNotFoundException] is how the platform reports that. Letting it propagate
     * would crash the viewer over a tap that was always going to be a dead end on that specific
     * device; the caller turns `false` into the card's own honest, visible message instead of a
     * crash the person never gets an explanation for.
     */
    fun launch(context: Context, intent: Intent, chooserTitle: String?): Boolean = try {
        val target = if (chooserTitle != null) Intent.createChooser(intent, chooserTitle) else intent
        context.startActivity(target)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
