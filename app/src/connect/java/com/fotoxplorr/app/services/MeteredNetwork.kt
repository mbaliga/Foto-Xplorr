package com.fotoxplorr.app.services

import android.content.Context
import android.net.ConnectivityManager

/** Connect flavor: asks the system whether the active network is metered (mobile data, or Wi-Fi marked metered). */
object MeteredNetwork {
    fun isMetered(context: Context): Boolean = runCatching {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.isActiveNetworkMetered
    }.getOrDefault(true)
}
