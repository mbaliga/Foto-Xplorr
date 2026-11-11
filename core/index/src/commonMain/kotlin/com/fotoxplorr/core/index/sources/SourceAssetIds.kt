package com.fotoxplorr.core.index.sources

import com.fotoxplorr.core.model.MediaId

/**
 * ADR-020 decision 2: the id band of assets that live in `fotoz-sources.db`. A source asset's
 * [MediaId] is `-(2^33) - n`, where `n >= 1` is the never-reused row number of the asset in that
 * database (SQLite AUTOINCREMENT, never a hash, so there is no collision to reason about).
 *
 * The band is disjoint from every other id the app already uses:
 *  - MediaStore `_ID`: `>= 0`;
 *  - open-with ids (`syntheticNegativeMediaId`): `-1 .. -2^31`;
 *  - `MediaId.EXTERNAL`: `Long.MIN_VALUE`, which is far below the largest row number this band
 *    accepts ([MAX_ROW]), and is rejected explicitly by [isSourceId].
 */
object SourceAssetIds {
    /** 2^33. Every source id is strictly below `-BAND_BASE`. */
    const val BAND_BASE: Long = 1L shl 33

    /** The lowest id an open-with asset can have (`-(2^31)`); every source id is far below it. */
    const val OPEN_WITH_FLOOR: Long = -(1L shl 31)

    /** Largest row number accepted: keeps `-(BAND_BASE) - n` clear of `Long.MIN_VALUE` (EXTERNAL). */
    const val MAX_ROW: Long = Long.MAX_VALUE - BAND_BASE - 1

    fun fromRow(row: Long): MediaId {
        require(row in 1..MAX_ROW) { "source asset row out of range: $row" }
        return MediaId(-BAND_BASE - row)
    }

    /** The asset row an id in the band belongs to, or `null` when [id] is not a source id. */
    fun rowOf(id: MediaId): Long? = if (isSourceId(id)) -id.value - BAND_BASE else null

    fun isSourceId(id: MediaId): Boolean =
        id.value < -BAND_BASE && id != MediaId.EXTERNAL

    /**
     * True when [id] could not belong to the MediaStore, the open-with or the EXTERNAL space:
     * the guard `mergeSourceAssets` uses when an id of the other world lands in the band.
     */
    fun isCollisionFree(id: MediaId): Boolean =
        isSourceId(id) && id.value < OPEN_WITH_FLOOR && id.value < 0 && id != MediaId.EXTERNAL
}
