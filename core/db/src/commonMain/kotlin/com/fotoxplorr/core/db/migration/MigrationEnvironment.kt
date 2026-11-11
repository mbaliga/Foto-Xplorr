package com.fotoxplorr.core.db.migration

/**
 * The non-database side effects ADR-011 §5's steps need: file I/O (`EXPORT`, `CLEANUP`) and
 * cutover state (`CUTOVER`, `CLEANUP`'s "second successful start" gate). Kept separate from
 * [LegacySource] (which only *reads*) since these *write* to the filesystem and app state --
 * `:app` provides the real implementation; JVM tests provide one backed by a temp directory and
 * an in-memory flag.
 */
interface MigrationEnvironment {
    /** ADR-011 §5 step 1: `filesDir/migration/pre-v2-<timestampMs>.json`. */
    suspend fun writeExportJson(json: String, timestampMs: Long)

    /** ADR-011 §5 step 1: copies (not moves) the seven old DB files into
     *  `filesDir/migration/pre-v2/`. The old files stay in their live location until
     *  [deleteOldStoresAtLiveLocation]. */
    suspend fun copyOldStoreFiles()

    /** ADR-011 §5 step 10, run only on the second successful app start after cutover: deletes
     *  the old DB files and asset-keyed preferences from their **live** locations. The
     *  `migration/` copies from [copyOldStoreFiles] are untouched (kept for 90 days). */
    suspend fun deleteOldStoresAtLiveLocation()

    /** ADR-011 §5 step 9: flips the flag `LibraryRuntime` reads to route every reader/writer to
     *  `fotoz.db` from here on. */
    suspend fun markCutoverComplete()

    suspend fun isCutoverComplete(): Boolean

    /** ADR-011 §5 step 10's "only on the second successful app start after cutover" -- call once
     *  per successful app start after [markCutoverComplete]; returns the new count. */
    suspend fun recordSuccessfulStartSinceCutover(): Int
}
