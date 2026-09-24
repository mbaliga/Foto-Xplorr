package com.fotoxplorr.core.db.gallery

import androidx.room.Dao
import androidx.room.Query
import com.fotoxplorr.core.db.entity.AssetEntity

/**
 * WP1.5's real SQL projections, replacing `GalleryProjection.kt`'s in-memory Kotlin filter/sort
 * passes over the app's full, unpaginated `List<MediaAsset>` mirror (ADR-011 Consequences: "WP1.5
 * removes it"). Covers the 11 [SmartAlbum]-equivalent queries and basic LIMIT/OFFSET paging.
 *
 * **Not wired into the live UI yet** -- two real gaps versus the Kotlin functions it's meant to
 * replace, deliberately left open in this first increment (see MASTER-PROGRESS.md's WP1.5
 * Decisions for the full reasoning):
 *  - **Locked-folder visibility** (`isPrivacyVisible` in the old code) is not applied here at
 *    all. It depends on which folders are unlocked *this session* -- ephemeral state, not a
 *    `folder_lock` column -- so it needs its own parameterized join, not built yet.
 *  - **Free-text search** (`matchesQuery`/`matchesGallerySearch`) is not applied here either.
 *
 * Every query applies the same base predicates `GalleryProjection.kt`'s `browsableAssets`/
 * `smartAlbumAssets` already establish, translated to SQL:
 *  - never a permanently-deleted row (implicit -- a deleted row is gone from `asset` entirely,
 *    `ON DELETE CASCADE` per ADR-011 §6)
 *  - never trashed (`trashed = 0`), except [observeTrash] itself
 *  - archived exclusion -- owner decision 2 (24 Sep 2026), applied to every query except
 *    [observeArchived] (the album *is* the archived set) and [observeTrash] (a deliberate
 *    exception: an item a user trashed must stay findable in Trash regardless of its archived
 *    state -- see Decisions)
 *  - sensitive exclusion, toggleable via [hideSensitive] (matches the old `hideSensitive` flag)
 *  - the video-visibility toggle via [showVideos] (matches the old `preferences.showVideos`)
 *
 * `limit`/`offset` are plain LIMIT/OFFSET paging -- not yet the real windowed-Flow/Paging-3
 * mechanism MASTER-PLAN.md §2.3 asks for (a separate, deliberately deferred decision; see
 * Decisions).
 */
@Dao
interface GalleryProjectionDao {

    // ---- the everyday (main timeline) projection -----------------------------------------

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun everyday(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    // ---- smart albums ----------------------------------------------------------------------

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE favorite = 1) " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeFavorites(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND date_taken_ms >= :sinceMs " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeRecent(sinceMs: Long, hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND mime LIKE 'video/%' " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeVideos(hideSensitive: Boolean, limit: Int, offset: Int): List<AssetEntity>

    /** Name OR containing-folder contains "screenshot", case-insensitive -- `LIKE` is
     *  case-insensitive for ASCII in SQLite by default, matching `MediaAsset.isScreenshot`'s own
     *  `.lowercase()`-based check for the same ASCII-only case this app's screenshot filenames
     *  actually use. */
    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND (display_name LIKE '%screenshot%' OR bucket_name LIKE '%screenshot%') " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeScreenshots(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    /** [com.fotoxplorr.core.db.entity.TraitEntity.animated] -- not `mime`-sniffed, matches the
     *  old code's own `animatedIds` (a real byte-sniff result), not a MIME guess. */
    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND asset_id IN (SELECT asset_id FROM trait WHERE animated = 1) " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeAnimated(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND size_bytes >= :thresholdBytes " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeLargeFiles(thresholdBytes: Long, hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    /**
     * [com.fotoxplorr.core.db.entity.AssetEntity]'s own `(size_bytes, width, height, mime)`
     * grouping, matching `GalleryProjection.duplicateCandidateIds` exactly: within each group,
     * the member with the smallest `(date_taken_ms, date_modified_ms, asset_id)` tuple is the
     * "keeper" and is excluded (`rn = 1`); every other member is a duplicate candidate. The
     * `ROW_NUMBER()`/`COUNT(*) OVER` window functions need SQLite 3.25+, which
     * `BundledSQLiteDriver` ships well above.
     */
    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND asset_id IN (" +
            "  SELECT asset_id FROM (" +
            "    SELECT asset_id, " +
            "      ROW_NUMBER() OVER (PARTITION BY size_bytes, width, height, mime " +
            "        ORDER BY date_taken_ms ASC, date_modified_ms ASC, asset_id ASC) AS rn, " +
            "      COUNT(*) OVER (PARTITION BY size_bytes, width, height, mime) AS grp_count " +
            "    FROM asset WHERE trashed = 0 AND size_bytes > 0 AND width > 0 AND height > 0" +
            "  ) WHERE grp_count > 1 AND rn > 1" +
            ") " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeDuplicates(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE sensitive = 1) " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeSensitive(showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    /** No archived exclusion -- this album *is* `archived = 1`. */
    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 1) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeArchived(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    /**
     * No archived exclusion -- a deliberate exception (see this interface's own KDoc): an item
     * the user trashed must stay findable in Trash regardless of whether it was also archived,
     * so they can restore or permanently delete it. Matches today's behaviour exactly (the old
     * `smartAlbumAssets`'s `TRASH` branch doesn't filter on `archivedIds` either).
     */
    @Query(
        "SELECT * FROM asset WHERE trashed = 1 " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeTrash(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:hideSensitive = 0 OR asset_id NOT IN (SELECT asset_id FROM asset_user WHERE sensitive = 1)) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND NOT EXISTS (SELECT 1 FROM asset_keyword WHERE asset_keyword.asset_id = asset.asset_id) " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeUntagged(hideSensitive: Boolean, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>

    // ---- device-folder album drill-down -----------------------------------------------------

    @Query(
        "SELECT * FROM asset WHERE trashed = 0 " +
            "AND asset_id IN (SELECT asset_id FROM asset_user WHERE archived = 0) " +
            "AND (:showVideos = 1 OR mime NOT LIKE 'video/%') " +
            "AND folder_key = :folderKey " +
            "ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun observeFolder(folderKey: String, showVideos: Boolean, limit: Int, offset: Int): List<AssetEntity>
}

/** Same constants as `GalleryProjection.kt`'s own (`RECENT_WINDOW_MILLIS`, `LARGE_FILE_THRESHOLD_BYTES`)
 *  -- duplicated deliberately: this module doesn't (and shouldn't) depend on `:app`. */
object GalleryProjectionDefaults {
    const val RECENT_WINDOW_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    const val LARGE_FILE_THRESHOLD_BYTES: Long = 20L * 1024 * 1024
}
