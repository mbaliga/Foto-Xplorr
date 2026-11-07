package com.fotoxplorr.app.services

import android.content.Context

/** Offline flavor: no network is ever used, so nothing is metered. Same name as the Connect one. */
object MeteredNetwork {
    @Suppress("UNUSED_PARAMETER")
    fun isMetered(context: Context): Boolean = false
}
