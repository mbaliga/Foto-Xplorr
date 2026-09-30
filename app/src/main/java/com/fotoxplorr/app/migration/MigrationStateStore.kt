package com.fotoxplorr.app.migration

import android.content.Context
import java.io.IOException

/**
 * ADR-011 §5 steps 9 and 10: whether the catalogue has cut over to `fotoz.db`, and how many
 * successful app starts have happened since. This is the flag `LibraryRuntime` reads to route
 * every reader and writer.
 *
 * It lives in its own preferences file instead of `fotoz.db`, because it's the one fact that has
 * to be readable before anything decides which database to open. It's also kept apart from the
 * V1 `foto_xplorr_legacy_migration` file (`organize/LegacyCatalogMigration`), which guards an
 * unrelated, older migration. This follows the same small-preferences-store shape as
 * `media/PrefsScanWatermark` and `organize/LegacyCatalogMigration`.
 *
 * Writes use `commit()`, not `apply()`, and throw if the write fails. If the process dies after
 * [markCutoverComplete] returns but before an `apply()` reached disk, the next start would see
 * `CUTOVER` recorded as done in `migration_progress` with this flag still false. The app would
 * then keep reading the old stores for good, because `MigrationToV2.run()` finds nothing left to
 * do. The blocking cost is one tiny file write, at most twice per install.
 *
 * Reads are plain and synchronous. `LibraryRuntime` can call [isCutoverComplete] from its
 * constructor.
 */
class MigrationStateStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCutoverComplete(): Boolean = preferences.getBoolean(KEY_CUTOVER_COMPLETE, false)

    fun successfulStartsSinceCutover(): Int = preferences.getInt(KEY_STARTS_SINCE_CUTOVER, 0)

    /** Idempotent: a second call keeps the start count already recorded instead of resetting it,
     *  which would push CLEANUP back. */
    fun markCutoverComplete() = synchronized(LOCK) {
        if (isCutoverComplete()) return@synchronized
        val written = preferences.edit()
            .putBoolean(KEY_CUTOVER_COMPLETE, true)
            .putInt(KEY_STARTS_SINCE_CUTOVER, 0)
            .commit()
        if (!written) throw IOException("Could not persist the catalogue cutover flag")
    }

    /**
     * Counts one successful app start since cutover and returns the new total. Returns 0 without
     * counting when cutover hasn't happened, so a start before cutover can never bring CLEANUP
     * forward.
     */
    fun recordSuccessfulStartSinceCutover(): Int = synchronized(LOCK) {
        if (!isCutoverComplete()) return@synchronized 0
        val next = successfulStartsSinceCutover() + 1
        val written = preferences.edit().putInt(KEY_STARTS_SINCE_CUTOVER, next).commit()
        if (!written) throw IOException("Could not persist the post-cutover start count")
        next
    }

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_migration_state"
        const val KEY_CUTOVER_COMPLETE = "catalogue_v2_cutover_complete"
        const val KEY_STARTS_SINCE_CUTOVER = "successful_starts_since_cutover"

        /** Shared by every instance: they all read and modify the same file. */
        val LOCK = Any()
    }
}
