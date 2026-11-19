package com.fotoxplorr.app.receive

/** What a received file is, for the choice sheet and the saver. */
enum class ReceiveKind { IMAGE, VIDEO, OTHER }

/**
 * One uri another app shared, as the activity found it, before any decision.
 *
 * @param mimeType what the provider answered for the uri, or null.
 * @param displayName the file name the provider reported, or null; only its extension is used.
 * @param typeHint the intent's own type (often only a family with a wildcard); only its image or
 *   video family is used, and only when the provider's answer and the name say nothing.
 * @param readable whether the activity could open the uri for reading; answered by the caller,
 *   off the main thread, so this file stays free of Android.
 */
data class ReceiveCandidate(
    val uri: String,
    val mimeType: String? = null,
    val displayName: String? = null,
    val typeHint: String? = null,
    val readable: Boolean = true,
)

/** A received file worth acting on: readable, `content://` and an image or a video. */
data class ReceiveItem(val uri: String, val mimeType: String?, val kind: ReceiveKind, val displayName: String?)

/**
 * The received files, cleaned up once, before anything is shown or written.
 *
 * [items] keeps the order the sender gave, with a repeated uri dropped, and holds only images and
 * videos, at most [MAX_ITEMS] of them. The three counters say what did not make it, so the sheet
 * can be honest about it.
 */
data class ReceivePlan(
    val items: List<ReceiveItem>,
    val unreadable: Int,
    val notMedia: Int,
    val overCap: Int,
) {
    val images: Int get() = items.count { it.kind == ReceiveKind.IMAGE }
    val videos: Int get() = items.count { it.kind == ReceiveKind.VIDEO }
    val isEmpty: Boolean get() = items.isEmpty()
    val wasCapped: Boolean get() = overCap > 0

    companion object {
        /** Many senders put a whole album in one share; past this the rest is left out. */
        const val MAX_ITEMS = 500
    }
}

/**
 * Makes the plan from [candidates].
 *
 * A uri that is not `content://` is unreadable here, whatever the caller found: a `file://` uri
 * would have this app read any path it can on the sender's say-so. Something that is neither an
 * image nor a video is counted as not media and dropped. The cap applies to what is left, so a
 * pile of unreadable entries never uses up the room.
 */
fun planReceive(candidates: List<ReceiveCandidate>, cap: Int = ReceivePlan.MAX_ITEMS): ReceivePlan {
    require(cap > 0) { "cap must be positive" }
    val seen = HashSet<String>()
    var unreadable = 0
    var notMedia = 0
    val usable = ArrayList<ReceiveItem>()
    for (candidate in candidates) {
        if (!seen.add(candidate.uri)) continue
        if (!candidate.readable || !candidate.uri.startsWith(CONTENT_SCHEME, ignoreCase = true)) {
            unreadable++
            continue
        }
        val kind = classifyReceived(candidate.mimeType, candidate.displayName, candidate.typeHint)
        if (kind == ReceiveKind.OTHER) {
            notMedia++
            continue
        }
        usable += ReceiveItem(candidate.uri, concreteMime(candidate.mimeType), kind, candidate.displayName)
    }
    return ReceivePlan(
        items = usable.take(cap),
        unreadable = unreadable,
        notMedia = notMedia,
        overCap = (usable.size - cap).coerceAtLeast(0),
    )
}

/**
 * Image, video or other, by the most trustworthy answer first: the provider's type, then the file
 * extension, then the intent's own family. A wildcard answer is not a type.
 */
fun classifyReceived(mimeType: String?, displayName: String?, typeHint: String? = null): ReceiveKind {
    kindOfMime(concreteMime(mimeType))?.let { return it }
    val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    when (extension) {
        in IMAGE_EXTENSIONS -> return ReceiveKind.IMAGE
        in VIDEO_EXTENSIONS -> return ReceiveKind.VIDEO
    }
    // A concrete type that is neither (a PDF) must not be rescued by a loose hint.
    if (concreteMime(mimeType) != null) return ReceiveKind.OTHER
    val hint = typeHint?.trim()?.lowercase().orEmpty()
    return when {
        hint.startsWith("image/") -> ReceiveKind.IMAGE
        hint.startsWith("video/") -> ReceiveKind.VIDEO
        else -> ReceiveKind.OTHER
    }
}

/** The type without parameters and in lower case, or null for nothing, a wildcard or a malformed value. */
fun concreteMime(mimeType: String?): String? {
    val clean = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (clean.isEmpty() || clean.endsWith("/*") || !clean.contains('/')) return null
    return clean
}

private fun kindOfMime(mime: String?): ReceiveKind? = when {
    mime == null -> null
    mime.startsWith("image/") -> ReceiveKind.IMAGE
    mime.startsWith("video/") -> ReceiveKind.VIDEO
    else -> null
}

private const val CONTENT_SCHEME = "content://"

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "dng")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "3gp", "m4v")
