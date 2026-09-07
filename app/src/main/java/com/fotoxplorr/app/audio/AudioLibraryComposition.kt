package com.fotoxplorr.app.audio

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * [com.fotoxplorr.app.spatial.SpatialExperience]'s exact shape and reason for existing: the
 * AUDIO destination is rendered deep inside `DestinationContent`
 * (`com.fotoxplorr.app.gallery.DestinationBrowserScreen`), which has no parameter carrying this
 * app's audio catalogue or a way to start playback — threading one through every intermediate
 * composable's parameter list (or worse, hacking a second copy of the audio state down there)
 * would be a much larger, more invasive change than reading the same composition-local pattern
 * `com.fotoxplorr.app.gallery.GalleryScreen` already establishes for Places.
 */
data class AudioLibraryExperience(
    val assets: List<AudioAsset>,
    /** Starts playback of [AudioAsset] with the given list as its queue — see
     *  `com.fotoxplorr.app.audio.AudioPlayerScreen` for what "queue" means here (position within
     *  this list, not a separately editable playlist). */
    val onPlay: (AudioAsset, List<AudioAsset>) -> Unit,
)

val LocalAudioLibrary = staticCompositionLocalOf<AudioLibraryExperience?> { null }
