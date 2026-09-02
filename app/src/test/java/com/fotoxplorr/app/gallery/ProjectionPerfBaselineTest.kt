package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import org.junit.Test
import java.sql.DriverManager
import kotlin.io.path.createTempFile
import kotlin.system.measureNanoTime

/**
 * FX-005: the JVM performance baseline — the projections timed at 10k / 100k / 500k over
 * the same [SyntheticCatalogue] the goldens use, plus a real-SQLite-file read of the
 * catalogue table's exact shape via JDBC.
 *
 * **Off by default.** Run with `FX_PERF=true` and copy the printed table into
 * `docs/perf/baseline-jvm.md`; the WP2 exit gate holds regressions under 10% against that
 * file. These are JVM numbers on whatever machine ran them — comparable against themselves
 * for regression detection, and NOT a claim about any phone. Device numbers come from the
 * Macrobenchmark module (`benchmarks/`), which is `[OWNER]`-run.
 *
 * Caveat on the SQLite half: `android.database.sqlite` cannot run on the JVM, so the
 * harness replays `CatalogueOpenHelper`'s schema and its `readAll` ordering over
 * `org.xerial:sqlite-jdbc`. It times the storage engine and the query shape, not the
 * Android binder/cursor stack.
 */
class ProjectionPerfBaselineTest {

    @Test
    fun `capture the baseline table`() {
        if (System.getenv("FX_PERF") != "true") return

        val rows = StringBuilder()
        rows.append("| Catalogue | everydayAssets | 11 smart albums | 9 destinations | sortAssets ×4 | timelineStops | SQLite readAll |\n")
        rows.append("|---|---|---|---|---|---|---|\n")

        for (size in listOf(10_000, 100_000, 500_000)) {
            val assets = SyntheticCatalogue.assets(size)
            val state = SyntheticCatalogue.state(assets)

            val everyday = median {
                everydayAssets(
                    assets, state.library.archivedIds, state.sensitiveIds,
                    state.lockedFolders, state.unlockedFolders, state.preferences, "",
                    state.library.tagsByMediaId,
                )
            }
            val smart = median {
                SmartAlbum.entries.forEach { album ->
                    smartAlbumAssets(
                        album, assets, state.favoriteIds, state.sensitiveIds,
                        state.library.archivedIds, state.library.tagsByMediaId,
                        state.lockedFolders, state.unlockedFolders, state.preferences,
                        SyntheticCatalogue.NOW_MILLIS,
                    )
                }
            }
            val destinations = median {
                HyleDestination.entries.forEach { destinationAssets(it, state) }
            }
            val sorts = median {
                GallerySort.entries.forEach { sortAssets(assets, it) }
            }
            val everydayProjected = everydayAssets(
                assets, state.library.archivedIds, state.sensitiveIds,
                state.lockedFolders, state.unlockedFolders, state.preferences, "",
                state.library.tagsByMediaId,
            )
            val stops = median { timelineStops(everydayProjected, java.time.ZoneOffset.UTC, java.util.Locale.UK) }
            val sqlite = sqliteReadAllMillis(assets)

            rows.append(
                "| ${"%,d".format(size)} | ${everyday} ms | ${smart} ms | ${destinations} ms | ${sorts} ms | ${stops} ms | ${sqlite} ms |\n",
            )
        }

        println("FX_PERF_BASELINE_BEGIN")
        println(rows)
        println("FX_PERF_BASELINE_END")
    }

    /** Median of five timed runs after two warmups, in whole milliseconds. */
    private fun median(block: () -> Unit): Long {
        repeat(2) { block() }
        val times = (1..5).map { measureNanoTime(block) }.sorted()
        return times[2] / 1_000_000
    }

    /**
     * `CatalogueOpenHelper.readAll`'s shape: full-table read in
     * `date_taken DESC, date_modified DESC, id DESC` order, every column materialised.
     */
    private fun sqliteReadAllMillis(assets: List<MediaAsset>): Long {
        val file = createTempFile("fx-perf", ".db").toFile()
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE media (
                        id INTEGER PRIMARY KEY, content_uri TEXT NOT NULL, display_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL, bucket_name TEXT, bucket_id INTEGER,
                        date_taken INTEGER NOT NULL, date_modified INTEGER NOT NULL,
                        width INTEGER NOT NULL, height INTEGER NOT NULL, size_bytes INTEGER NOT NULL,
                        duration_millis INTEGER NOT NULL DEFAULT 0, relative_path TEXT,
                        is_favorite INTEGER NOT NULL, is_trashed INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                it.execute("CREATE INDEX media_order_idx ON media(date_taken DESC, date_modified DESC)")
            }
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO media VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { insert ->
                assets.forEach { a ->
                    insert.setLong(1, a.id.value); insert.setString(2, a.contentUriString)
                    insert.setString(3, a.displayName); insert.setString(4, a.mimeType)
                    insert.setString(5, a.bucketName); insert.setObject(6, a.bucketId)
                    insert.setLong(7, a.dateTakenMillis); insert.setLong(8, a.dateModifiedSeconds)
                    insert.setInt(9, a.width); insert.setInt(10, a.height)
                    insert.setLong(11, a.sizeBytes); insert.setLong(12, a.durationMillis)
                    insert.setString(13, a.relativePath)
                    insert.setInt(14, 0); insert.setInt(15, if (a.isTrashed) 1 else 0)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            conn.commit()
            conn.autoCommit = true

            var count = 0
            val elapsed = measureNanoTime {
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT * FROM media ORDER BY date_taken DESC, date_modified DESC, id DESC",
                    ).use { rs ->
                        while (rs.next()) {
                            // Materialise every column, as the cursor mapping does.
                            rs.getLong(1); rs.getString(2); rs.getString(3); rs.getString(4)
                            rs.getString(5); rs.getObject(6); rs.getLong(7); rs.getLong(8)
                            rs.getInt(9); rs.getInt(10); rs.getLong(11); rs.getLong(12)
                            rs.getString(13); rs.getInt(14); rs.getInt(15)
                            count++
                        }
                    }
                }
            }
            check(count == assets.size)
            return elapsed / 1_000_000
        }
    }
}
