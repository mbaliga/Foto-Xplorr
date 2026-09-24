package com.fotoxplorr.app.media

import android.os.Build

/** Which MediaStore collection a written file belongs to — the three collections
 *  [mediaStoreRelativePath] has a distinct allowed-folder list and fallback for. */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

/**
 * Where a copy or conversion of a file that came from [sourceRelativePath] should be saved within
 * [kind]'s MediaStore collection (P0-07).
 *
 * A save that reuses the source's own `RELATIVE_PATH` verbatim fails outright for a source
 * MediaProvider itself would never have accepted an insert into — a WhatsApp image living under
 * `Android/media/com.whatsapp/…`, or anything under `Download/` — because MediaProvider rejects an
 * insert whose `RELATIVE_PATH` names a top-level folder that collection does not own (its own
 * "Primary directory … not allowed for [collection]" check). Rather than let that insert fail, a
 * source outside the allowed set falls back to this app's own folder within the collection.
 *
 * A source already inside an allowed folder keeps its exact path (including any subfolder) rather
 * than being flattened to the fallback — an edit of `DCIM/Camera/IMG_1.jpg` belongs beside the
 * original in `DCIM/Camera/`, not relocated to `Pictures/Foto Xplorr/`.
 *
 * Allowed top-level folders per collection, verified against the current MediaProvider behaviour
 * described at https://developer.android.com/training/data-storage/shared/media
 * ("Media location and other metadata" / the collection-to-directory table on that page):
 *  - Images: `DCIM`, `Pictures`.
 *  - Video: `DCIM`, `Movies`, `Pictures`.
 *  - Audio: `Music`, `Podcasts`, `Audiobooks`, `Ringtones`, `Notifications`, `Alarms`, and
 *    `Recordings` — but only from API 31 (`Build.VERSION_CODES.S`); the same page states
 *    `Recordings` "isn't available on Android 11 (API level 30) and lower".
 *
 * [sdkInt] defaults to the real running platform version and only exists as a parameter so
 * [MediaStoreDestinationTest] can exercise the `Recordings` API-level gate directly — this
 * environment has no emulator and only one Robolectric framework jar available offline (see the
 * Phase 0 brief's environment notes), so a test cannot simulate API 30 by asking Robolectric to
 * run under a different `@Config(sdk = ...)`.
 */
fun mediaStoreRelativePath(sourceRelativePath: String?, kind: MediaKind, sdkInt: Int = Build.VERSION.SDK_INT): String {
    val topFolder = sourceRelativePath
        ?.trim('/')
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }
        ?: return FALLBACK_RELATIVE_PATH.getValue(kind)
    return if (topFolder in allowedTopFolders(kind, sdkInt)) sourceRelativePath else FALLBACK_RELATIVE_PATH.getValue(kind)
}

private val FALLBACK_RELATIVE_PATH = mapOf(
    MediaKind.IMAGE to "Pictures/Foto Xplorr/",
    MediaKind.VIDEO to "Movies/Foto Xplorr/",
    MediaKind.AUDIO to "Music/Foto Xplorr/",
)

private val IMAGE_ALLOWED_TOP_FOLDERS = setOf("DCIM", "Pictures")
private val VIDEO_ALLOWED_TOP_FOLDERS = setOf("DCIM", "Movies", "Pictures")
private val AUDIO_ALLOWED_TOP_FOLDERS = setOf("Music", "Podcasts", "Audiobooks", "Ringtones", "Notifications", "Alarms")
private const val AUDIO_RECORDINGS_TOP_FOLDER = "Recordings"

private fun allowedTopFolders(kind: MediaKind, sdkInt: Int): Set<String> = when (kind) {
    MediaKind.IMAGE -> IMAGE_ALLOWED_TOP_FOLDERS
    MediaKind.VIDEO -> VIDEO_ALLOWED_TOP_FOLDERS
    MediaKind.AUDIO -> if (sdkInt >= Build.VERSION_CODES.S) {
        AUDIO_ALLOWED_TOP_FOLDERS + AUDIO_RECORDINGS_TOP_FOLDER
    } else {
        AUDIO_ALLOWED_TOP_FOLDERS
    }
}
