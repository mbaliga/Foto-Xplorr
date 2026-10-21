package com.fotoxplorr.app.openwith

import com.fotoxplorr.app.media.MediaAsset

/**
 * What an outside request to open one specific catalogued photo should do, given what the person
 * may see right now.
 *
 * Another app can name a photo this library holds (an `EXTRA_OPEN_MEDIA_ID` redirect, or a
 * `content://media/...` Uri in an open-with / share intent), but that does not entitle it to show
 * the photo: a locked folder stays locked, and an archived, trashed or sensitive-hidden photo
 * stays out of sight, exactly as in every gallery surface. The caller therefore never opens such a
 * photo as an "external" file either -- that would put it on screen by a side door -- it only
 * tells the person it was not opened.
 */
sealed interface OpenRequestLookup {
    /** In the catalogue and browsable: open it. */
    data class Found(val asset: MediaAsset) : OpenRequestLookup

    /** In the catalogue but filtered out by [com.fotoxplorr.app.gallery.browsableAssets]. */
    data object Hidden : OpenRequestLookup

    /** Not in the catalogue (yet): the library may still be loading, or the file is not ours. */
    data object Unknown : OpenRequestLookup
}

/**
 * Resolves [requestedId] against [allAssets] (the raw repository list) and [browsable] (that same
 * list after `browsableAssets`, passed in so the one definition of "may be shown" stays in
 * `GalleryProjection.kt` and cannot drift from a copy made here).
 */
fun lookupOpenRequest(
    allAssets: List<MediaAsset>,
    browsable: List<MediaAsset>,
    requestedId: Long,
): OpenRequestLookup {
    browsable.firstOrNull { it.id.value == requestedId }?.let { return OpenRequestLookup.Found(it) }
    return if (allAssets.any { it.id.value == requestedId }) OpenRequestLookup.Hidden else OpenRequestLookup.Unknown
}

/**
 * The run of photos the viewer had open before the process died, rebuilt from plain ids.
 *
 * Only [browsable] photos come back: a locked folder is locked again after process death (the
 * unlocked set lives in memory), and a photo trashed, archived or hidden in the meantime must not
 * reappear just because its id was saved. Null when the photo that was open ([openId]) is not
 * among them, i.e. there is nothing to reopen.
 */
fun restoreViewerRun(browsable: List<MediaAsset>, savedIds: List<Long>, openId: Long): List<MediaAsset>? {
    val byId = browsable.associateBy { it.id.value }
    val restored = savedIds.mapNotNull { byId[it] }.ifEmpty { listOfNotNull(byId[openId]) }
    return restored.takeIf { run -> run.any { it.id.value == openId } }
}

/**
 * The MediaStore row id behind [uri] when it names a MediaStore row, else null.
 *
 * Pure string work (no `android.net.Uri`) so it is testable on the JVM. The authority is compared
 * after dropping the optional `<userId>@` prefix, which Android resolves to the same provider: an
 * exact `== "media"` test would let `content://0@media/...` through as an unrelated external file.
 * Photo-picker Uris share the authority but their trailing number is a picker id, not a row id, so
 * they are not rows.
 */
fun mediaStoreRowId(uri: String): Long? {
    val body = uri.substringBefore('?').substringBefore('#')
    if (!body.startsWith(CONTENT_PREFIX, ignoreCase = true)) return null
    val afterScheme = body.substring(CONTENT_PREFIX.length)
    val authority = afterScheme.substringBefore('/').substringAfter('@')
    if (!authority.equals("media", ignoreCase = true)) return null
    val segments = afterScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
    if (segments.firstOrNull()?.startsWith("picker", ignoreCase = true) == true) return null
    return segments.lastOrNull()?.toLongOrNull()?.takeIf { it >= 0 }
}

internal const val CONTENT_PREFIX = "content://"
