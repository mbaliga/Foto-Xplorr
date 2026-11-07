package com.fotoxplorr.app.sync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Connect build's sign-in seam: the system browser out, [OAuthRedirectActivity] back. Same fully-qualified
 * name as the Offline stub. NOT run by any test and never run on a device.
 */
object OAuthPlatform {
    const val available: Boolean = true

    fun signIn(context: Context): SyncOAuth = OAuthSignIn(SyncTransports.create(), AndroidOAuthBrowser(context))
}

/**
 * Opens the sign-in page in a Custom Tab when the phone's browser offers one, else as an ordinary browser page
 * (no WebView, no library: the Custom Tab request is the documented session extra with no session, which a
 * supporting browser reads as "show me as a tab"; a browser that does not simply ignores it). Waits for the
 * redirect for at most [TIMEOUT_MILLIS]; the wait ends early if the sign-in is abandoned.
 */
internal class AndroidOAuthBrowser(private val context: Context) : OAuthBrowser {
    override suspend fun authorize(url: String): String? {
        val waiting = OAuthRendezvous.begin(OAuthRendezvous.stateOf(url))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtras(Bundle().apply { putBinder(CUSTOM_TAB_SESSION, null as IBinder?) })
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            OAuthRendezvous.abandon(waiting)
            return null
        }
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { waiting.await() }
        } catch (e: CancellationException) {
            // Either this sign-in was replaced by a newer one (answer: none) or the caller itself was cancelled (rethrow).
            currentCoroutineContext().ensureActive()
            null
        } finally {
            OAuthRendezvous.abandon(waiting)
        }
    }

    private companion object {
        const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"
        const val TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * Receives the browser's redirect (the custom scheme in the Connect manifest), hands the address to the waiting
 * sign-in in this process, and brings the app forward. The address carries the one-time code: it is never
 * logged and is not kept here. If nothing is waiting (the process was killed during the trip) it is dropped and
 * the person taps Sign in again.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { OAuthRendezvous.deliver(it) }
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launch)
        }
        finish()
    }
}
