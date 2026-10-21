package com.fotoxplorr.ut.bridge

/**
 * The one real, tested query surface [fotoz_query_page] routes to: `:core:db`'s own
 * [com.fotoxplorr.core.db.gallery.GalleryProjectionDao] -- WP1.5a's SQL smart-album/everyday
 * projections, real SQL against the real ADR-011 schema, proven by that DAO's own 15 JVM tests.
 *
 * "Not wired into the live UI yet" (per that DAO's own KDoc and MASTER-PROGRESS.md's WP1.5
 * Decisions) is a claim about `:app`'s Android `GalleryScreen.kt`, which this WP does not touch --
 * it is a genuinely different, additional consumer of the exact same real queries, which is why
 * wiring it here is honest rather than jumping ahead of WP1.5's own live-UI cutover. The two
 * documented gaps that DAO itself does not close (locked-folder visibility; the richer
 * folder/tag/OCR/label half of free-text search) are not closed here either -- see
 * [fotoz_query_page]'s own KDoc for the narrower substitute this module applies for the latter.
 */
internal enum class SmartAlbum(val wireName: String) {
    EVERYDAY("EVERYDAY"),
    FAVORITES("FAVORITES"),
    RECENT("RECENT"),
    VIDEOS("VIDEOS"),
    SCREENSHOTS("SCREENSHOTS"),
    ANIMATED("ANIMATED"),
    LARGE_FILES("LARGE_FILES"),
    DUPLICATES("DUPLICATES"),
    SENSITIVE("SENSITIVE"),
    ARCHIVED("ARCHIVED"),
    TRASH("TRASH"),
    UNTAGGED("UNTAGGED"),
    FOLDER("FOLDER"),
    ;

    companion object {
        fun of(wireName: String): SmartAlbum? = entries.firstOrNull { it.wireName == wireName }
    }
}
