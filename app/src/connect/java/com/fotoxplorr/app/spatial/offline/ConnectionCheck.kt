package com.fotoxplorr.app.spatial.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What a download would run over, as far as the platform can say. */
internal enum class Connection {
    /** No network that reaches the internet. Nothing is created or started. */
    NONE,

    /** Mobile data or another metered network: the person is asked first. */
    METERED,

    UNMETERED,
}

/**
 * Whether a download would work, and whether it would use mobile data. Read at the moment of the tap
 * and passed to the guard as `metered`; the Connect build is the only one allowed to ask the platform
 * (the Offline build has no network permission at all).
 */
internal fun interface ConnectionCheck {
    fun connection(): Connection
}

/**
 * The answer from the platform's three facts about the active network. No network, or one that
 * does not reach the internet, is [Connection.NONE] and never "metered": asking "Download over
 * mobile data?" with no connection at all would create an area that can fetch nothing.
 */
internal fun classify(hasActiveNetwork: Boolean, reachesInternet: Boolean, metered: Boolean): Connection = when {
    !hasActiveNetwork || !reachesInternet -> Connection.NONE
    metered -> Connection.METERED
    else -> Connection.UNMETERED
}

/**
 * The platform's answer for the active network (it needs ACCESS_NETWORK_STATE, declared in this
 * flavour's manifest). When it cannot be had at all (no connectivity service, no permission) the
 * answer is [Connection.METERED], so the person is asked rather than a large download being
 * started on a guess. Not run on a device.
 */
internal class PlatformConnectionCheck(context: Context) : ConnectionCheck {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun connection(): Connection = try {
        val service = manager
        if (service == null) {
            Connection.METERED
        } else {
            val network = service.activeNetwork
            val capabilities = network?.let { service.getNetworkCapabilities(it) }
            classify(
                hasActiveNetwork = network != null,
                reachesInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                metered = service.isActiveNetworkMetered,
            )
        }
    } catch (_: SecurityException) {
        Connection.METERED
    }
}
