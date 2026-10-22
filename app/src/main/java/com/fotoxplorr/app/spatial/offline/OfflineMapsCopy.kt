package com.fotoxplorr.app.spatial.offline

/**
 * Fixed wording for the offline-maps feature, one short sentence each.
 *
 * Lives here, not in `ui/HelpCopy.kt`; the integrator decides where each line is shown. Both
 * flavours compile this file, so the Offline build can show [STUB_ROW] without any map code.
 */
object OfflineMapsCopy {

    /** The honest row in the Offline build, which has no network code at all. */
    const val STUB_ROW = "Offline maps need the Connect build."

    /** Shown beside the Download button. Tile requests really do reveal both. */
    const val PRIVACY = "Downloading shows the area and your IP address to the map provider."

    /** The credit the data licences require. */
    const val ATTRIBUTION = "© OpenStreetMap contributors, OpenMapTiles, OpenFreeMap"

    /** Shown when the engine cannot apply the provider's limit on parallel requests. */
    const val UNAVAILABLE = "Map downloads are unavailable in this build."

    /** Shading and the dark map style are loaded separately and are not part of a saved area. */
    const val NOT_SAVED = "Terrain shading and the dark map are not saved."

    const val REDUCED_DETAIL = "Saved with less detail to stay in the free limit."

    const val METERED = "Download over mobile data?"

    const val ROOM_NEEDED = "Delete a saved area to make room."

    // ---- The Data-tab row, the map pill and the pane. Labels first, then one sentence each. ----

    const val ROW_LABEL = "Offline maps"

    /** The Data-tab row's caption in the Connect build; the Offline build's caption is [STUB_ROW]. */
    const val ROW_CAPTION = "Save a map area for use without a connection."

    /** The pill on the embedded map. */
    const val PILL = "Save this area offline"

    const val HEADING_THIS_VIEW = "THIS VIEW"
    const val HEADING_PLACES = "PLACES IN YOUR PHOTOS"
    const val HEADING_SAVED = "SAVED AREAS"

    const val THIS_VIEW = "This view"
    const val DOWNLOAD = "Download"
    const val RESUME = "Resume"
    const val PAUSE = "Pause"
    const val DELETE = "Delete"
    const val KEEP = "Keep"
    const val RENAME = "Rename"
    const val DONE = "Done"
    const val CANCEL = "Cancel"
    const val NOT_NOW = "Not now"
    const val NAME_FIELD = "Name"

    const val EMPTY = "Open the map to save the area you are looking at."
    const val NOTHING_SAVED = "Nothing is saved yet."
    const val SAVED = "Saved."
    const val ONE_AT_A_TIME = "One download at a time."
    const val DATE_LINE = "Areas over the date line cannot be saved yet."
    const val ALREADY_SAVED = "Already in your saved areas."
    const val DELETE_FAILED = "That area could not be deleted."
    const val LIST_FAILED = "Saved areas could not be read."

    fun downloadWithSize(bytes: Long): String = "$DOWNLOAD, about ${megabytes(bytes)}"

    fun photos(count: Int): String = if (count == 1) "1 photo" else "$count photos"

    fun quota(count: Int, usedBytes: Long): String =
        "$count of ${OfflineMapLimits.MAX_REGIONS} areas saved, ${megabytesBare(usedBytes)} of " +
            "${megabytesBare(OfflineMapLimits.MAX_TOTAL_BYTES)} MB used."

    fun meteredBody(bytes: Long): String = "It will use about ${megabytes(bytes)}."

    fun deleteTitle(name: String): String = "Delete $name?"

    fun deleteBody(bytes: Long): String = "This frees about ${megabytes(bytes)}."

    fun downloading(percent: Int?): String = if (percent == null) "Downloading." else "Downloading, $percent%."

    /** A size for a person: whole decimal megabytes, rounded up so the figure never promises less than it takes. */
    fun megabytes(bytes: Long): String = "${megabytesBare(bytes).coerceAtLeast(1L)} MB"

    private fun megabytesBare(bytes: Long): Long = (bytes.coerceAtLeast(0L) + 999_999L) / 1_000_000L

    fun tooLarge(reason: TooLargeReason): String = when (reason) {
        TooLargeReason.TOO_MANY_TILES -> "This area is too big to save."
        TooLargeReason.TOO_MANY_BYTES -> "This area would take too much space."
    }

    fun failure(reason: FailureReason, tooLarge: TooLargeReason? = null): String = when (reason) {
        FailureReason.TOO_LARGE -> tooLarge(tooLarge ?: TooLargeReason.TOO_MANY_TILES)
        FailureReason.NO_AREA -> "Pick an area, not a point."
        FailureReason.OVER_CAP -> "Stopped at the size limit."
        FailureReason.SERVER -> "The map provider returned errors."
        FailureReason.ENGINE -> "The download could not continue."
    }

    fun paused(reason: PauseReason): String = when (reason) {
        PauseReason.USER -> "Paused."
        PauseReason.CONNECTION -> "Paused, no connection."
        PauseReason.RESTARTED -> "Paused, tap resume to continue."
    }

    fun room(verdict: RoomVerdict): String? = when (verdict) {
        RoomVerdict.Ok -> null
        is RoomVerdict.TooManyRegions, is RoomVerdict.OverTotalBytes -> ROOM_NEEDED
    }
}
