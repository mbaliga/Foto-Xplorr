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
    /** Whether the app currently holds `READ_MEDIA_AUDIO` (or its pre-Tiramisu equivalent) — see
     *  `com.fotoxplorr.app.hasAudioPermission`'s KDoc for why this is granted separately from,
     *  and often later than, the photo/video permission the rest of this composition local's
     *  siblings assume (P0-10). */
    val permissionGranted: Boolean,
    /** Launches the audio permission request. Only ever called from the Audio pane's own empty
     *  state, per the brief: audio access is requested when the user opens the Audio library, not
     *  eagerly alongside photo/video access. */
    val onRequestPermission: () -> Unit,
)

val LocalAudioLibrary = staticCompositionLocalOf<AudioLibraryExperience?> { null }
