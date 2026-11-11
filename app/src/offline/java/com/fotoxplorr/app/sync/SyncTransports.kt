package com.fotoxplorr.app.sync

import android.content.Context

/**
 * The Offline build's side of the network seam: there is none. Same fully-qualified names as the
 * Connect build's file in `src/connect/`, chosen by the build variant. WebDAV and S3 report that they
 * need the Connect build; the folder destination never asks for a transport.
 */
object SyncTransports {
    const val available: Boolean = false

    fun create(): HttpTransport = HttpTransport { _, _ ->
        throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
    }
}

/** The Offline build has no connection to look at; only the folder destination exists here and it never asks. */
object SyncConnectivity {
    @Suppress("UNUSED_PARAMETER")
    fun current(context: Context): SyncNetwork = SyncNetwork.NONE
}
