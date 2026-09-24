package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.AssetKeywordEntity
import com.fotoxplorr.core.db.entity.AssetKeywordRejectionEntity
import com.fotoxplorr.core.db.entity.KeywordEntity
import com.fotoxplorr.core.model.AssetId

/** Backs the tag/auto-tag slice of `LibraryStore`'s facade (ADR-011 Consequences). */
@Dao
interface KeywordDao {
    /** `keyword`'s own `UNIQUE(parent_id, name)` does not dedupe root keywords (see
     *  [KeywordEntity]'s own doc) -- callers creating a root keyword must call this first and
     *  only [insert] a new one when it returns null. */
    @Query("SELECT * FROM keyword WHERE parent_id IS NULL AND name = :name")
    suspend fun findRoot(name: String): KeywordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(keyword: KeywordEntity): Long

    @Query("SELECT * FROM keyword WHERE parent_id IS NULL ORDER BY name")
    suspend fun getAllRoot(): List<KeywordEntity>

    // REPLACE, not IGNORE: the migration catch-up pass (MigrationToV2.runUserData) relies on
    // this to CORRECT an existing link's origin (USER <-> AUTO) on a (asset_id, keyword_id)
    // conflict, not silently keep the old one.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: AssetKeywordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun linkAll(links: List<AssetKeywordEntity>)

    @Query("DELETE FROM asset_keyword WHERE asset_id = :assetId AND keyword_id = :keywordId")
    suspend fun unlink(assetId: AssetId, keywordId: Long)

    @Query("SELECT * FROM asset_keyword WHERE asset_id = :assetId")
    suspend fun linksFor(assetId: AssetId): List<AssetKeywordEntity>

    /** Every asset currently linked to one keyword -- the migration catch-up pass's
     *  reconciliation unit (see `MigrationToV2.runUserData`'s tag section). */
    @Query("SELECT * FROM asset_keyword WHERE keyword_id = :keywordId")
    suspend fun linksForKeyword(keywordId: Long): List<AssetKeywordEntity>

    @Query(
        "SELECT k.keyword_id FROM keyword k JOIN asset_keyword ak ON ak.keyword_id = k.keyword_id " +
            "WHERE ak.asset_id = :assetId AND k.name = :name AND k.parent_id IS NULL",
    )
    suspend fun rootKeywordIdIfLinked(assetId: AssetId, name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reject(rejection: AssetKeywordRejectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rejectAll(rejections: List<AssetKeywordRejectionEntity>)

    @Query("SELECT COUNT(*) > 0 FROM asset_keyword_rejection WHERE asset_id = :assetId AND keyword_id = :keywordId")
    suspend fun isRejected(assetId: AssetId, keywordId: Long): Boolean
}
