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
