package com.fotoxplorr.core.db.sources

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourcesDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: SourceRecord): Long

    @Query("SELECT * FROM source WHERE source_row = :row")
    suspend fun get(row: Long): SourceRecord?

    @Query("SELECT * FROM source WHERE kind = :kind AND root_locator = :rootLocator")
    suspend fun findByLocator(kind: String, rootLocator: String): SourceRecord?

    @Query("SELECT * FROM source ORDER BY source_row")
    suspend fun getAll(): List<SourceRecord>

    @Query("SELECT * FROM source ORDER BY source_row")
    fun observeAll(): Flow<List<SourceRecord>>

    /** State only; never touches assets, so going OFFLINE or REVOKED deletes nothing. */
    @Query("UPDATE source SET state = :state WHERE source_row = :row")
    suspend fun setState(row: Long, state: String)

    /** The person removing a source (forgets it; files are never touched). Cascades to its rows. */
    @Query("DELETE FROM source WHERE source_row = :row")
    suspend fun remove(row: Long)
}

@Dao
interface SourceFoldersDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(folder: SourceFolderRecord): Long

    @Query("UPDATE source_folder SET path = :path, last_seen_run = :run WHERE source_row = :sourceRow AND document_id = :documentId")
    suspend fun touch(sourceRow: Long, documentId: String, path: String, run: Long)

    @Query("SELECT * FROM source_folder WHERE source_row = :sourceRow ORDER BY path")
    suspend fun forSource(sourceRow: Long): List<SourceFolderRecord>
}

@Dao
interface SourceAssetsDao {
    /** IGNORE, never REPLACE: replacing would delete the row and mint a new number, changing the media id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(asset: SourceAssetRecord): Long

    /** Refreshes the listing facts of an existing row, keeping its row number and any enrichment. */
    @Query(
        "UPDATE source_asset SET display_name = :displayName, mime = :mime, size_bytes = :sizeBytes, " +
            "date_modified_ms = :dateModifiedMs, path = :path, content_uri = :contentUri, present = 1, " +
            "last_seen_run = :run WHERE source_row = :sourceRow AND document_id = :documentId",
    )
    suspend fun refresh(
        sourceRow: Long,
        documentId: String,
        displayName: String,
        mime: String,
        sizeBytes: Long,
        dateModifiedMs: Long,
        path: String,
        contentUri: String?,
        run: Long,
    )

    @Query("SELECT * FROM source_asset WHERE source_row = :sourceRow AND document_id = :documentId")
    suspend fun find(sourceRow: Long, documentId: String): SourceAssetRecord?

    @Query("SELECT * FROM source_asset WHERE asset_row = :row")
    suspend fun get(row: Long): SourceAssetRecord?

    @Query("SELECT * FROM source_asset WHERE source_row = :sourceRow ORDER BY asset_row")
    suspend fun forSource(sourceRow: Long): List<SourceAssetRecord>

    /** Everything the gallery may merge: present rows of any source; offline sources still list. */
    @Query("SELECT * FROM source_asset WHERE present = 1 ORDER BY asset_row")
    fun observePresent(): Flow<List<SourceAssetRecord>>

    @Query("SELECT COUNT(*) FROM source_asset WHERE source_row = :sourceRow")
    suspend fun count(sourceRow: Long): Int

    @Query("UPDATE source_asset SET width = :width, height = :height, duration_ms = :durationMs, date_taken_ms = :dateTakenMs, enrich_state = 1 WHERE asset_row = :row")
    suspend fun setEnriched(row: Long, width: Int, height: Int, durationMs: Long, dateTakenMs: Long)

    @Query("UPDATE source_asset SET enrich_state = 2, enrich_attempts = enrich_attempts + 1 WHERE asset_row = :row")
    suspend fun setUnreadable(row: Long)

    /**
     * Mark-and-sweep, as a soft mark: rows of [sourceRow] not stamped with [run] stop being
     * `present`; none is deleted. The guard is in the SQL itself: it does nothing unless [run]
     * is a COMPLETED full run of that very source AND the source is ONLINE (TRAPS #8). Returns
     * the number of rows marked.
     */
    @Query(
        "UPDATE source_asset SET present = 0 WHERE source_row = :sourceRow AND last_seen_run <> :run AND present = 1 " +
            "AND EXISTS (SELECT 1 FROM index_run r WHERE r.run_id = :run AND r.source_row = :sourceRow " +
            "AND r.status = 'COMPLETED' AND r.full = 1) " +
            "AND EXISTS (SELECT 1 FROM source s WHERE s.source_row = :sourceRow AND s.state = 'ONLINE')",
    )
    suspend fun sweepMissing(sourceRow: Long, run: Long): Int
}

@Dao
interface IndexRunsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun start(run: IndexRunRecord): Long

    @Update
    suspend fun update(run: IndexRunRecord)

    @Query("SELECT * FROM index_run WHERE run_id = :runId")
    suspend fun get(runId: Long): IndexRunRecord?

    /** The checkpoint: cursor and counter together, one statement. */
    @Query("UPDATE index_run SET cursor = :cursor, files_seen = :filesSeen WHERE run_id = :runId AND status = 'RUNNING'")
    suspend fun checkpoint(runId: Long, cursor: String?, filesSeen: Long)

    @Query("UPDATE index_run SET status = :status, finished_ms = :finishedMs, error = :error WHERE run_id = :runId")
    suspend fun finish(runId: Long, status: String, finishedMs: Long, error: String?)

    /** The newest unfinished run of a source: what a walk resumes from after a process death. */
    @Query("SELECT * FROM index_run WHERE source_row = :sourceRow AND status = 'RUNNING' ORDER BY run_id DESC LIMIT 1")
    suspend fun latestRunning(sourceRow: Long): IndexRunRecord?
}
