package com.fotoxplorr.app.widget

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/**
 * What a widget tap asks [com.fotoxplorr.app.FotoXplorrActivity] to do, and the rule the Activity
 * applies before doing it.
 *
 * A widget can be minutes or days out of date (its process may not have run since a photo was
 * archived, flagged sensitive or moved into a protected folder), so the Activity never trusts the
 * ids a tap carries. [resolveWidgetOpen] re-checks every one against the same home-screen rule the
 * widget was drawn with ([WidgetPrivacy]) and the stores as they stand now, and a tap on anything
 * that no longer passes opens the app's home instead. It is deliberately separate from
 * `EXTRA_OPEN_MEDIA_ID`, whose caller (Open with) already holds the file's uri.
 */
internal object WidgetLaunch {
    /** Marks an Activity intent as a widget tap; also keeps it apart from VIEW/SEND handling. */
    const val ACTION_OPEN_FROM_WIDGET = "com.fotoxplorr.app.action.OPEN_FROM_WIDGET"

    /** The tapped item's `MediaId.value`. */
    const val EXTRA_MEDIA_ID = "com.fotoxplorr.app.widget.EXTRA_MEDIA_ID"

    /** The ids the viewer may page through from there, in the widget's order (at most [WIDGET_RUN_LIMIT]). */
    const val EXTRA_RUN_IDS = "com.fotoxplorr.app.widget.EXTRA_RUN_IDS"

    const val NO_MEDIA_ID = -1L
}

internal data class WidgetOpenRequest(val mediaId: Long, val runIds: List<Long>)

internal data class WidgetOpen(val run: List<MediaAsset>, val selected: MediaAsset)

/**
 * The viewer run for a widget tap, or null when the tapped item may no longer be drawn on the home
 * screen (or is gone). Ids in the run that may no longer be drawn are dropped, never opened. The
 * rule is the widgets' own picture rule ([WidgetPrivacy.picturedAssets]), so a tap can never open
 * something a widget would not draw now.
 */
internal fun resolveWidgetOpen(
    request: WidgetOpenRequest,
    assets: List<MediaAsset>,
    archivedIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    lockedFolders: Set<String>,
    identityIds: Set<MediaId>,
): WidgetOpen? {
    if (request.mediaId == WidgetLaunch.NO_MEDIA_ID) return null
    val visibleById = WidgetPrivacy.picturedAssets(assets, archivedIds, sensitiveIds, lockedFolders, identityIds)
        .associateBy { it.id.value }
    val selected = visibleById[request.mediaId] ?: return null
    val run = request.runIds
        .distinct()
        .take(WIDGET_RUN_LIMIT)
        .mapNotNull(visibleById::get)
        .takeIf { selected in it }
        ?: listOf(selected)
    return WidgetOpen(run = run, selected = selected)
}

/** Request codes per widget instance: slot 0 opens the app, slots 1 and up open items. */
internal const val SLOTS_PER_WIDGET = 16

/**
 * A PendingIntent request code unique per widget instance and tap target, so one widget's taps
 * never overwrite another's extras (`FLAG_UPDATE_CURRENT` replaces extras of an equal intent).
 */
internal fun widgetRequestCode(widgetId: Int, slot: Int): Int {
    require(slot in 0 until SLOTS_PER_WIDGET) { "slot $slot out of range" }
    return widgetId * SLOTS_PER_WIDGET + slot
}
