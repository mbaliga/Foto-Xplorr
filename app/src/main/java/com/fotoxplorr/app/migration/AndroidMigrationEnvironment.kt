package com.fotoxplorr.app.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.core.db.migration.MigrationEnvironment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [MigrationEnvironment]: ADR-011 §5's file side effects (`EXPORT`, `CLEANUP`) and its
 * cutover state (`CUTOVER`, and `CLEANUP`'s second-start gate, both kept in [MigrationStateStore]).
 *
 * Layout under `filesDir/migration/`:
 * - `pre-v2-<timestampMs>.json` is `MigrationToV2`'s export.
 * - `pre-v2/<db name>` is a copy of each old SQLite file ([LegacyStoreFiles.DATABASES]).
 * - `pre-v2/shared_prefs/<name>.xml` is a copy of each asset-keyed preferences file. This goes
 *   beyond what the interface asks for (it says DB files only), and it's deliberate: the export
 *   JSON `MigrationToV2` writes doesn't include the private-folder salts and hashes, although
 *   ADR-011 §5 step 1 lists private-folder preferences. Without this copy, `CLEANUP`
 *   ([deleteOldStoresAtLiveLocation]) would leave no pre-migration backup of the locks at all.
 *
 * Nothing here deletes anything under `migration/`. The ADR's 90-day retention is a separate job.
 */
class AndroidMigrationEnvironment(context: Context) : MigrationEnvironment {
    private val appContext = context.applicationContext
    private val state = MigrationStateStore(appContext)

    private val migrationDir: File get() = File(appContext.filesDir, "migration")
    private val backupDir: File get() = File(migrationDir, "pre-v2")

    override suspend fun writeExportJson(json: String, timestampMs: Long) = withContext(Dispatchers.IO) {
        val target = File(ensureDirectory(migrationDir), "pre-v2-$timestampMs.json")
        writeAtomically(target, json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Copies each old SQLite file that exists, along with its `-wal`/`-journal` sidecar, since
     * committed rows can still sit in the WAL.
     *
     * ## Why each copy is verified
     * The live stores keep writing while this runs (the UI stays on them until cutover), and
     * nothing here can pause them. A helper is private to its store, and a read-write connection
     * from here could switch a live file's journal mode. A byte copy taken mid-commit or
     * mid-checkpoint can therefore be torn. Each copy is opened (the COPY, never the live file) and
     * must pass `PRAGMA quick_check`, or it's retried. Opening it also folds its WAL into the main
     * file (Android's default journal mode on a plain `openDatabase` is not WAL), so the backup
     * that's kept is one self-contained file. This is best effort: quick_check catches structural
     * tearing, not two pages that are each internally valid but from different commits.
     */
    override suspend fun copyOldStoreFiles() = withContext(Dispatchers.IO) {
        val databasesDir = ensureDirectory(backupDir)
        for (name in LegacyStoreFiles.DATABASES) {
            val live = appContext.getDatabasePath(name)
            if (!live.isFile) continue
            copyVerified(live, File(databasesDir, name))
        }
        val prefsBackupDir = ensureDirectory(File(backupDir, "shared_prefs"))
        for (name in LegacyStoreFiles.ASSET_KEYED_PREFERENCES) {
            val live = sharedPreferencesFile(name)
            val preferences = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (!live.isFile && preferences.all.isEmpty()) continue
            // Every one of these stores writes with apply(), so the XML on disk can lag behind
            // what the app (and the export JSON) already holds in memory. An empty commit()
            // writes the current in-memory state synchronously, with the same contents, so the
            // copy below matches the export.
            preferences.edit().commit()
            if (!live.isFile) continue
            val target = File(prefsBackupDir, live.name)
            writeAtomically(target, live.readBytes())
        }
    }

    /**
     * ADR-011 §5 step 10. Deletes the six old SQLite files and their sidecars with
     * `Context.deleteDatabase`, and the four asset-keyed preferences files. Each preferences file
     * is cleared with a synchronous commit before `deleteSharedPreferences` (API 24+, minSdk
     * here is 26). The clear matters because `Context` caches one SharedPreferences instance per
     * name for the whole process. Deleting only the file would leave any instance a caller still
     * holds full of the old data, and its next write would recreate the file with all of it.
     *
     * The caller must ensure nothing in this process still has an old SQLite file open.
     * Unlinking an open file doesn't fail on Linux, but the holder would keep writing into a
     * deleted inode.
     */
    override suspend fun deleteOldStoresAtLiveLocation() = withContext(Dispatchers.IO) {
        for (name in LegacyStoreFiles.DATABASES) {
            appContext.deleteDatabase(name)
        }
        for (name in LegacyStoreFiles.ASSET_KEYED_PREFERENCES) {
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            appContext.deleteSharedPreferences(name)
        }
    }

    override suspend fun markCutoverComplete() = withContext(Dispatchers.IO) { state.markCutoverComplete() }

    override suspend fun isCutoverComplete(): Boolean = withContext(Dispatchers.IO) { state.isCutoverComplete() }

    override suspend fun recordSuccessfulStartSinceCutover(): Int =
        withContext(Dispatchers.IO) { state.recordSuccessfulStartSinceCutover() }

    // ---- helpers ---------------------------------------------------------------------------

    private fun copyVerified(live: File, target: File) {
        val staging = File(target.parentFile, "${target.name}.partial")
        repeat(COPY_ATTEMPTS) {
            deleteWithSidecars(staging)
            live.copyTo(staging, overwrite = true)
            for (suffix in COPIED_SIDECARS) {
                val sidecar = File(live.path + suffix)
                if (sidecar.isFile) sidecar.copyTo(File(staging.path + suffix), overwrite = true)
            }
            if (passesQuickCheck(staging)) {
                deleteWithSidecars(target)
                // quick_check's open/close normally folds the WAL into the main file. Any
                // sidecar still left belongs to the copy and moves with it.
                for (suffix in COPIED_SIDECARS) {
                    val sidecar = File(staging.path + suffix)
                    if (sidecar.isFile && !sidecar.renameTo(File(target.path + suffix))) {
                        throw IOException("Could not move ${sidecar.name} into place")
                    }
                }
                File(staging.path + "-shm").delete()
                if (!staging.renameTo(target)) throw IOException("Could not move ${staging.name} into place")
                return
            }
        }
        deleteWithSidecars(staging)
        throw IOException("No consistent copy of ${live.name} after $COPY_ATTEMPTS attempts")
    }

    private fun passesQuickCheck(copy: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(
            copy.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
        }
    }.getOrDefault(false)

    private fun deleteWithSidecars(file: File) {
        file.delete()
        for (suffix in COPIED_SIDECARS + "-shm") File(file.path + suffix).delete()
    }

    private fun sharedPreferencesFile(name: String): File =
        File(File(appContext.dataDir, "shared_prefs"), "$name.xml")

    private fun ensureDirectory(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        return dir
    }

    /** Write, then rename, so a crash mid-write never leaves a truncated file under the final
     *  name. `renameTo` replaces an existing target on the same filesystem. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not move ${temp.name} into place")
        }
    }

    private companion object {
        const val COPY_ATTEMPTS = 3

        /** `-shm` is left out on purpose: SQLite rebuilds it from the WAL, and a copied one can
         *  only be stale. */
        val COPIED_SIDECARS = listOf("-wal", "-journal")
    }
}
