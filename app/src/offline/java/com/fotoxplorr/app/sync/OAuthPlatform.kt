package com.fotoxplorr.app.sync

import android.content.Context

/**
 * The Offline build's side of the sign-in seam: there is none (Google Drive and Dropbox need the network, so
 * they are Connect-build kinds). Same fully-qualified name as the Connect build's file in `src/connect/`.
 */
object OAuthPlatform {
    const val available: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun signIn(context: Context): SyncOAuth = UnavailableSyncOAuth
}
