package com.fotoxplorr.app.viewer

/** What is actually playing, read off ExoPlayer's own reported [androidx.media3.common.Format]s
 *  once available -- for the details room. Every field is null/zero until the player has
 *  something to report; a null field means "not yet known" or "no such track", not "unsupported". */
data class VideoTrackInfo(
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
)
