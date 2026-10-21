package com.fotoxplorr.app.video

/**
 * Which top-level MediaStore collection a `RELATIVE_PATH` has to sit under to be legal for that
 * collection — `MediaStore.Video.Media`/`MediaStore.Audio.Media` both reject (or silently
 * relocate, depending on OS version) a path outside their own primary directories, and a value
 * copied verbatim from a source this app does not control (an arbitrary `Download/` or
 * `Android/media/...` path picked up via SAF, say) is exactly the kind of value that can carry
 * one home. Validating in pure code, rather than trusting the source, keeps a bad path from ever
 * reaching [android.content.ContentValues].
 */
private val VIDEO_RELATIVE_PATH_PREFIXES = listOf("DCIM/", "Pictures/", "Movies/")
private val AUDIO_RELATIVE_PATH_PREFIXES =
    listOf("Music/", "Podcasts/", "Ringtones/", "Alarms/", "Notifications/", "Recordings/")

const val FALLBACK_VIDEO_RELATIVE_PATH = "Movies/Foto Xplorr/"
const val FALLBACK_AUDIO_RELATIVE_PATH = "Music/Foto Xplorr/"

/** [candidate] (typically the source's own `RELATIVE_PATH`) if it sits under one of the video
 *  collection's legal primaries, otherwise [FALLBACK_VIDEO_RELATIVE_PATH]. */
fun validatedVideoRelativePath(candidate: String?): String =
    validatedRelativePath(candidate, VIDEO_RELATIVE_PATH_PREFIXES, FALLBACK_VIDEO_RELATIVE_PATH)

/** Same idea as [validatedVideoRelativePath] for the audio collection's own legal primaries. */
fun validatedAudioRelativePath(candidate: String?): String =
    validatedRelativePath(candidate, AUDIO_RELATIVE_PATH_PREFIXES, FALLBACK_AUDIO_RELATIVE_PATH)

private fun validatedRelativePath(candidate: String?, allowedPrefixes: List<String>, fallback: String): String {
    val normalized = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
    return if (allowedPrefixes.any { normalized.startsWith(it, ignoreCase = true) }) normalized else fallback
}
