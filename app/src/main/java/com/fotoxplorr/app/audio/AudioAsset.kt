package com.fotoxplorr.app.audio

import android.net.Uri
import com.fotoxplorr.app.media.MediaId

/**
 * One standalone audio file — a song, a recording, a podcast episode — as distinct from
 * [com.fotoxplorr.app.media.MediaAsset], which models something you can decode to pixels.
 *
 * A SEPARATE type on purpose, not `MediaAsset` with `width`/`height` set to zero: `MediaAsset` is
 * read, unguarded, by roughly eighty call sites across this app (the photo/video editors, on-device
 * recognition, embeddings/similarity, the spatial/GPS index, share/export, moment detection) that
 * all assume "this is something you can decode to a bitmap or a video frame". Reusing it for audio
 * would mean every one of those either growing a defensive "is this actually visual" check it does
 * not have today, or silently mishandling an audio row the first time one reached it. A type this
 * app never hands to that code cannot reach it by accident.
 *
 * Reuses [MediaId] rather than inventing a second id wrapper: both are `content://media/...` row
 * ids from the same MediaStore, and `MediaId` itself carries no photo/video-specific meaning.
 */
data class AudioAsset(
    val id: MediaId,
    val contentUriString: String,
    val displayName: String,
    /** The track title MediaStore extracted from tags, or [displayName] when a file carries none
     *  (an unlabelled recording, a voice memo) — never blank, so a list row always has something
     *  to show. */
    val title: String,
    val artist: String?,
    val album: String?,
    val mimeType: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
) {
    val contentUri: Uri
        get() = Uri.parse(contentUriString)
}
