package com.fotoxplorr.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.fotoxplorr.app.audio.AudioAsset

/**
 * Builds a playable [MediaItem] for [PlaybackService], carrying enough [MediaMetadata] for the
 * system notification and lock-screen surface to show a title, an artist/album line and artwork.
 *
 * Deliberately keyed on a plain [Uri] rather than [AudioAsset] directly: [PlaybackService] is
 * built to be the one playback surface for the whole app, and the video viewer's own background-
 * audio hand-off (see the workstream this shipped alongside) needs to feed it a video's content
 * Uri the exact same way — a Uri-in/MediaItem-out helper is the shape both callers need, audio
 * today and video next, without either owning the other's asset type. [AudioAsset.toMediaItem]
 * below is audio's own thin adapter onto it.
 */
fun buildMediaItem(
    mediaId: String,
    contentUri: Uri,
    title: String,
    artist: String? = null,
    album: String? = null,
    artworkUri: Uri? = null,
    trackNumber: Int? = null,
    durationMillis: Long? = null,
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkUri)
        .setTrackNumber(trackNumber)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .apply { durationMillis?.let(::setDurationMs) }
        .build()

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(contentUri)
        .setMediaMetadata(metadata)
        .build()
}

/** [AudioAsset.id] as a [MediaItem.mediaId] — the one identifier every layer of the playback
 *  stack (controller, service, notification) agrees on, so a [MediaItem] the service hands back
 *  can always be matched back to the [AudioAsset] it came from. */
fun AudioAsset.toMediaId(): String = id.value.toString()

fun AudioAsset.toMediaItem(): MediaItem = buildMediaItem(
    mediaId = toMediaId(),
    contentUri = contentUri,
    title = title,
    artist = artist,
    album = album,
    artworkUri = albumArtUri,
    trackNumber = trackNumber,
    durationMillis = durationMillis.takeIf { it > 0L },
)
