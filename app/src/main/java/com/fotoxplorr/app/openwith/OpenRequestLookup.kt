package com.fotoxplorr.app.openwith

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.ByteArrayOutputStream

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

    /**
     * A `content://media` row the catalogue does not hold and MediaStore would not describe either,
     * so there is no way to tell whether it sits in a locked folder. Refused rather than opened.
     */
    data object Unverified : OpenRequestLookup
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
 * What a MediaStore row that the catalogue has not scanned yet (a photo written a moment ago, the
 * scan debounce has not fired) may do, judged from the row MediaStore itself describes.
 *
 * The folder identity of [row] is built from the same bucket / relative-path columns the scanner
 * reads, so a photo just saved into a locked folder is held to the lock like every catalogued
 * one. [Unknown][OpenRequestLookup.Unknown] here means "not catalogued, but nothing hides it":
 * the caller may read it as an external file. A null [row] (the query failed, the row is gone, or
 * this app has no access to it) is [Unverified][OpenRequestLookup.Unverified]: refused.
 *
 * @param browsableOf the one definition of "may be shown", applied to the single stub row.
 */
fun lookupUncataloguedRow(row: MediaAsset?, browsableOf: (List<MediaAsset>) -> List<MediaAsset>): OpenRequestLookup =
    when {
        row == null -> OpenRequestLookup.Unverified
        browsableOf(listOf(row)).isEmpty() -> OpenRequestLookup.Hidden
        else -> OpenRequestLookup.Unknown
    }

/**
 * A stand-in [MediaAsset] for a MediaStore row, carrying only what the privacy rules read (id,
 * folder identity, trashed). Never shown or stored: it exists so [lookupUncataloguedRow] can ask
 * `browsableAssets` the same question the catalogue's own rows are asked.
 */
fun mediaStoreRowAsset(
    rowId: Long,
    contentUri: String,
    displayName: String,
    mimeType: String,
    bucketId: Long?,
    bucketName: String?,
    relativePath: String?,
    isTrashed: Boolean,
): MediaAsset = MediaAsset(
    id = MediaId(rowId),
    contentUriString = contentUri,
    displayName = displayName,
    mimeType = mimeType,
    bucketName = bucketName,
    bucketId = bucketId,
    dateTakenMillis = 0L,
    dateModifiedSeconds = 0L,
    width = 0,
    height = 0,
    sizeBytes = 0L,
    relativePath = relativePath,
    isFavorite = false,
    isTrashed = isTrashed,
)

/** How a Uri relates to the MediaStore provider; see [classifyMediaStoreUri]. */
sealed interface MediaStoreUri {
    /** Another provider, another scheme, or a photo-picker Uri: not a library row. */
    data object NotMediaStore : MediaStoreUri

    /** A MediaStore row, by its `_ID`. */
    data class Row(val id: Long) : MediaStoreUri

    /** The MediaStore provider, but no row id could be read from it. Never opened as an external
     *  file: whatever the provider makes of it, the hidden-photo rules could not be applied. */
    data object Unreadable : MediaStoreUri
}

/**
 * Classifies [uri] the way the platform reads it, so the hidden-photo guard cannot be walked
 * around by spelling the same Uri differently.
 *
 * Android percent-decodes a Uri's authority and each path segment before the provider matches
 * them, so `%6Dedia`, `.../media/%34%32` and `.../media/42/thumbnail` all reach the same row as
 * the plain form. Pure string work (no `android.net.Uri`) so it is testable on the JVM: the
 * query and fragment are cut off first, the authority is decoded and stripped of its optional
 * `<userId>@` prefix (everything up to the LAST `@`, as the platform does), each path segment is
 * decoded once, and the last numeric segment is the row id. Photo-picker Uris share the authority
 * but their trailing number is a picker id, not a row id, so they are [MediaStoreUri.NotMediaStore].
 */
fun classifyMediaStoreUri(uri: String): MediaStoreUri {
    val body = uri.substringBefore('?').substringBefore('#')
    if (!body.startsWith(CONTENT_PREFIX, ignoreCase = true)) return MediaStoreUri.NotMediaStore
    val afterScheme = body.substring(CONTENT_PREFIX.length)
    val authority = percentDecode(afterScheme.substringBefore('/')).substringAfterLast('@')
    if (!authority.equals("media", ignoreCase = true)) return MediaStoreUri.NotMediaStore
    val segments = afterScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.map(::percentDecode)
    if (segments.firstOrNull()?.startsWith("picker", ignoreCase = true) == true) return MediaStoreUri.NotMediaStore
    val id = segments.lastOrNull { it.toLongOrNull() != null }?.toLong()
    return if (id != null && id >= 0) MediaStoreUri.Row(id) else MediaStoreUri.Unreadable
}

/** The MediaStore row id behind [uri] when it names one, else null; see [classifyMediaStoreUri],
 *  which also says when a media-provider Uri is unreadable (a caller must refuse that, not open it). */
fun mediaStoreRowId(uri: String): Long? = (classifyMediaStoreUri(uri) as? MediaStoreUri.Row)?.id

/** `android.net.Uri.decode`: `%XX` runs are UTF-8 bytes, anything malformed is left as written. */
private fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val out = StringBuilder(text.length)
    val pending = ByteArrayOutputStream()
    fun flush() {
        if (pending.size() > 0) {
            out.append(String(pending.toByteArray(), Charsets.UTF_8))
            pending.reset()
        }
    }
    var i = 0
    while (i < text.length) {
        val hi = if (text[i] == '%' && i + 2 < text.length) hexValue(text[i + 1]) else -1
        val lo = if (hi >= 0) hexValue(text[i + 2]) else -1
        if (hi >= 0 && lo >= 0) {
            pending.write(hi * 16 + lo)
            i += 3
        } else {
            flush()
            out.append(text[i])
            i++
        }
    }
    flush()
    return out.toString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

internal const val CONTENT_PREFIX = "content://"
