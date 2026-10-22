package com.fotoxplorr.app.spatial.offline

import android.content.Context
import android.net.ConnectivityManager

/**
 * Whether a download would use mobile data. Read at the moment of the tap and passed to the guard
 * as `metered`; the Connect build is the only one allowed to ask the platform (the Offline build
 * has no network permission at all).
 */
internal fun interface MeteredCheck {
    fun isMetered(): Boolean
}

/**
 * The platform's answer for the active network. When it cannot be had (no connectivity service, no
 * permission, no active network) the answer is "metered", so the person is asked rather than a
 * large download being started on a guess.
 */
internal class PlatformMeteredCheck(context: Context) : MeteredCheck {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean = try {
        manager?.isActiveNetworkMetered ?: true
    } catch (_: SecurityException) {
        true
    }
}
