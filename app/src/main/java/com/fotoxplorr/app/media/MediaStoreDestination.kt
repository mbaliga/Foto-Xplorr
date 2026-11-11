package com.fotoxplorr.app.media

/** Which MediaStore collection a written file belongs to — the two collections
 *  [mediaStoreRelativePath] has a distinct allowed-folder list and fallback for. */
enum class MediaKind { IMAGE, VIDEO }

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
 * original in `DCIM/Camera/`, not relocated to `Pictures/Fotoz/`.
 *
 * Builds before the product was renamed saved their fallback copies to `Pictures/Foto Xplorr/`
 * and `Movies/Foto Xplorr/`. Those folders are never renamed or moved and
 * the scanners index them by MediaStore id like any other folder, so nothing is lost; a source
 * already inside one keeps its exact path (the top folder is an allowed one), so an edit of an
 * older copy still lands beside it. Only a fresh fallback now goes to the `Fotoz` folders.
 *
 * Allowed top-level folders per collection, verified against the current MediaProvider behaviour
 * described at https://developer.android.com/training/data-storage/shared/media
 * ("Media location and other metadata" / the collection-to-directory table on that page):
 *  - Images: `DCIM`, `Pictures`.
 *  - Video: `DCIM`, `Movies`, `Pictures`.
 */
fun mediaStoreRelativePath(sourceRelativePath: String?, kind: MediaKind): String {
    val topFolder = sourceRelativePath
        ?.trim('/')
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }
        ?: return FALLBACK_RELATIVE_PATH.getValue(kind)
    return if (topFolder in allowedTopFolders(kind)) sourceRelativePath else FALLBACK_RELATIVE_PATH.getValue(kind)
}

private val FALLBACK_RELATIVE_PATH = mapOf(
    MediaKind.IMAGE to "Pictures/Fotoz/",
    MediaKind.VIDEO to "Movies/Fotoz/",
)

private val IMAGE_ALLOWED_TOP_FOLDERS = setOf("DCIM", "Pictures")
private val VIDEO_ALLOWED_TOP_FOLDERS = setOf("DCIM", "Movies", "Pictures")

private fun allowedTopFolders(kind: MediaKind): Set<String> = when (kind) {
    MediaKind.IMAGE -> IMAGE_ALLOWED_TOP_FOLDERS
    MediaKind.VIDEO -> VIDEO_ALLOWED_TOP_FOLDERS
}
