package com.fotoxplorr.app.index

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.db.dao.SourceDao
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.db.newMediaStoreSource
import com.fotoxplorr.core.index.SyncEngine
import com.fotoxplorr.core.index.SyncResult
import com.fotoxplorr.core.model.SourceId

/**
 * Drives [SyncEngine] over every MediaStore volume this device currently has, once per call --
 * the Android-glue counterpart to [com.fotoxplorr.core.db.migration.MigrationToV2]'s `runSources`
 * + `runAssets`, but ongoing rather than one-shot. Owns exactly the volume-discovery bookkeeping
 * `SyncEngine` itself deliberately doesn't (it operates one [com.fotoxplorr.core.index.Source] +
 * one [com.fotoxplorr.core.db.entity.SourceEntity] at a time): which volumes exist right now,
 * which known sources just disappeared (-> `markSourceOffline`) or just came back (->
 * `markSourceOnline`), and which are new (-> a fresh [SourceEntity] row, `newMediaStoreSource`).
 */
class AndroidMediaSync(
    private val appContext: Context,
    private val sourceDao: SourceDao,
    assetDao: AssetDao,
    assetUserDao: AssetUserDao,
) {
    private val engine = SyncEngine(sourceDao, assetDao, assetUserDao)

    /** One pass over every current volume. [partialAccess] and [nowMs] are threaded straight into
     *  [SyncEngine.sync] -- see its own doc. */
    suspend fun syncAll(partialAccess: Boolean, nowMs: Long): List<SyncResult> {
        val currentVolumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(appContext).sorted()
        } else {
            emptyList()
        }
        // API 26-28: no getExternalVolumeNames at all -- exactly one fallback locator, matching
        // MigrationToV2.primarySourceId() exactly (volumes.firstOrNull() ?: "external", and
        // `volumes` is always empty here) so both land on the same SourceEntity row rather than
        // each creating their own.
        val volumesToSync = currentVolumes.ifEmpty { listOf(FALLBACK_VOLUME) }
        val reachable = volumesToSync.toHashSet()

        val knownSources = sourceDao.getAll().filter { it.kind == SourceKind.MEDIASTORE_VOLUME }
        for (known in knownSources) {
            if (known.rootLocator !in reachable && known.state != SourceState.OFFLINE) {
                engine.markSourceOffline(known.sourceId)
            }
        }

        val results = ArrayList<SyncResult>(volumesToSync.size)
        for (volume in volumesToSync) {
            var sourceRow = sourceDao.findByLocator(SourceKind.MEDIASTORE_VOLUME, volume)
            if (sourceRow == null) {
                val id = sourceDao.insert(newMediaStoreSource(volume))
                sourceRow = sourceDao.get(SourceId(id))!!
            } else if (sourceRow.state == SourceState.OFFLINE) {
                engine.markSourceOnline(sourceRow.sourceId)
                sourceRow = sourceDao.get(sourceRow.sourceId)!!
            }
            val source = AndroidMediaStoreSource(appContext, volume)
            results += engine.sync(source, sourceRow, partialAccess, nowMs)
        }
        return results
    }

    private companion object {
        const val FALLBACK_VOLUME = "external"
    }
}
