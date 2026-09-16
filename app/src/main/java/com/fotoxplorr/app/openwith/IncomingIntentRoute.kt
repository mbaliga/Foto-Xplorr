package com.fotoxplorr.app.openwith

/**
 * What an incoming ACTION_VIEW / ACTION_EDIT / ACTION_SEND(_MULTIPLE) intent should do inside the
 * app, once classified. One case per real destination -- the viewer, the editor, or the audio
 * player -- rather than a single "here is a Uri, go figure it out" so a caller can `when` over it
 * exhaustively instead of re-deriving the same image/video/audio branching a second time.
 */
sealed interface IncomingIntentRoute {
    /** A single image or video Uri, opened straight into the viewer. */
    data class ViewMedia(val uris: List<String>, val isVideo: Boolean) : IncomingIntentRoute

    /** A single audio Uri, opened straight into the player. */
    data class ViewAudio(val uri: String) : IncomingIntentRoute

    /** An image handed over specifically to be edited, per ACTION_EDIT's own contract. */
    data class EditImage(val uri: String) : IncomingIntentRoute

    /** The intent named an action/type this app answers in its manifest but could not be
     *  classified into anything usable -- no data, no stream, or a MIME type outside the three
     *  this app declares intent filters for. */
    data object Unhandled : IncomingIntentRoute
}

/**
 * Pure classification of an incoming intent, from the plain strings an
 * [android.content.Intent] carries rather than the Intent itself -- so this is testable with no
 * Android runtime at all.
 *
 * @param action `Intent.ACTION_VIEW`, `Intent.ACTION_EDIT`, `Intent.ACTION_SEND` or
 *   `Intent.ACTION_SEND_MULTIPLE`, passed as the plain string constant those fields hold.
 * @param dataUri `intent.data?.toString()`.
 * @param mimeType `intent.type` -- for a `content://` Uri with no explicit type, the caller
 *   resolves this via `ContentResolver.getType` before calling in; this function does no
 *   resolving of its own; a null value here is simply treated as "not classifiable".
 * @param streamUris [android.content.Intent.EXTRA_STREAM] (single or, for SEND_MULTIPLE, the
 *   whole `ArrayList`), already extracted as strings.
 */
fun classifyIncomingIntent(
    action: String?,
    dataUri: String?,
    mimeType: String?,
    streamUris: List<String> = emptyList(),
): IncomingIntentRoute {
    val kind = mediaKindFor(mimeType)
    return when (action) {
        ACTION_VIEW -> {
            val uri = dataUri ?: return IncomingIntentRoute.Unhandled
            when (kind) {
                MediaKind.IMAGE -> IncomingIntentRoute.ViewMedia(listOf(uri), isVideo = false)
                MediaKind.VIDEO -> IncomingIntentRoute.ViewMedia(listOf(uri), isVideo = true)
                MediaKind.AUDIO -> IncomingIntentRoute.ViewAudio(uri)
                MediaKind.UNKNOWN -> IncomingIntentRoute.Unhandled
            }
        }
        ACTION_EDIT -> {
            val uri = dataUri ?: return IncomingIntentRoute.Unhandled
            if (kind == MediaKind.IMAGE) IncomingIntentRoute.EditImage(uri) else IncomingIntentRoute.Unhandled
        }
        ACTION_SEND, ACTION_SEND_MULTIPLE -> {
            val uris = streamUris.ifEmpty { listOfNotNull(dataUri) }
            if (uris.isEmpty()) return IncomingIntentRoute.Unhandled
            when (kind) {
                MediaKind.IMAGE -> IncomingIntentRoute.ViewMedia(uris, isVideo = false)
                MediaKind.VIDEO -> IncomingIntentRoute.ViewMedia(uris, isVideo = true)
                // A shared audio file plays the first track; a batch share of audio has no
                // "queue" contract from the sending app, so only the first Uri is honoured.
                MediaKind.AUDIO -> IncomingIntentRoute.ViewAudio(uris.first())
                MediaKind.UNKNOWN -> IncomingIntentRoute.Unhandled
            }
        }
        else -> IncomingIntentRoute.Unhandled
    }
}

private enum class MediaKind { IMAGE, VIDEO, AUDIO, UNKNOWN }

private fun mediaKindFor(mimeType: String?): MediaKind = when {
    mimeType == null -> MediaKind.UNKNOWN
    mimeType.startsWith("image/") -> MediaKind.IMAGE
    mimeType.startsWith("video/") -> MediaKind.VIDEO
    mimeType.startsWith("audio/") -> MediaKind.AUDIO
    else -> MediaKind.UNKNOWN
}

// The literal values of the android.content.Intent constants this file classifies -- kept as
// plain strings (rather than importing android.content.Intent) so this file has no Android
// dependency at all and runs under plain JUnit.
private const val ACTION_VIEW = "android.intent.action.VIEW"
private const val ACTION_EDIT = "android.intent.action.EDIT"
private const val ACTION_SEND = "android.intent.action.SEND"
private const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
