package com.fotoxplorr.core.model

import kotlin.jvm.JvmInline

@JvmInline
value class MediaId(val value: Long) {
    companion object {
        /**
         * The id every ad-hoc asset gets: one built from an incoming `ACTION_VIEW` intent
         * (P0-18's `viewer.ExternalViewerActivity`) that the catalogue never scanned and never
         * will, since this app must never write anything about it to any store. Every REAL id
         * comes from MediaStore's own `_ID` column, which is never negative, so a negative id
         * already reads as "not really catalogued" everywhere this app checks one --
         * `favorites.FavoriteIdCodec` filters negative ids on both encode and decode for exactly
         * this reason. `Long.MIN_VALUE` specifically, not `-1`: `FotoXplorrActivity`'s own
         * `NO_MEDIA_ID = -1L` already means something else ("no selection") and the two must
         * never collide.
         */
        val EXTERNAL: MediaId = MediaId(Long.MIN_VALUE)
    }
}
