package com.fotoxplorr.app.backup

import android.content.Context
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.FolderLockRecord
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.spatial.GeoMetadata
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's stores a restore adds to ([RestoreApplier]). Every call is a merge: the tags go in
 * through the same `ImportMode.MERGE` the plain file uses, locks never replace a lock the phone has,
 * a hand-placed pin or a saved edit that exists is kept.
 */
internal class AndroidRestoreApplier(
    private val context: Context,
    private val libraryStore: LibraryStore,
    private val favoriteStore: FavoriteStore,
    private val sensitiveStore: SensitiveStore,
    private val privateFolderStore: PrivateFolderStore,
    private val geoRepository: GeoMetadataRepository,
    private val recipeStore: RecipeStore,
    private val catalogueIds: suspend () -> Set<Long>,
    private val requestScan: () -> Unit,
    private val catalogueWaitMillis: Long = CATALOGUE_WAIT_MILLIS,
) : RestoreApplier {
    private val metadata = MetadataBackupIo(context.contentResolver, libraryStore, favoriteStore, sensitiveStore)

    override suspend fun applyMetadata(prepared: MetadataBackup.PreparedRestore) {
        metadata.apply(prepared, ImportMode.MERGE)
    }

    override suspend fun applyLocks(locks: List<BackupSections.Lock>): LockResult = withContext(Dispatchers.IO) {
        val result = privateFolderStore.restoreLocks(locks.map { FolderLockRecord(it.folderKey, it.saltBase64, it.hashBase64) })
        // Stops the restore before a protected photo is written: without its lock it would be visible.
        if (result.rejected > 0) {
            throw BackupRefused("A folder lock in the backup could not be put back, so nothing was restored. Nothing on this phone was changed.")
        }
        LockResult(result.added, result.kept)
    }

    override suspend fun markSensitive(ids: Set<Long>) {
        withContext(Dispatchers.IO) { sensitiveStore.setSensitive(ids.mapTo(linkedSetOf()) { MediaId(it) }, true) }
    }

    override suspend fun applyPins(pins: List<BackupSections.Pin>): Int {
        if (pins.isEmpty()) return 0
        return geoRepository.importManualPins(pins.map { GeoMetadata(MediaId(it.mediaId), it.latitude, it.longitude, null, null) })
    }

    override suspend fun applyRecipes(recipes: List<RestoredRecipe>): Int = withContext(Dispatchers.IO) {
        recipes.count { recipeStore.importStored(it.mediaId, it.dateModifiedSeconds, it.recipe.stringify()) }
    }

    override suspend fun awaitCatalogue(ids: Set<Long>) {
        if (ids.isEmpty()) return
        requestScan()
        withTimeoutOrNull(catalogueWaitMillis) {
            while (!catalogueIds().containsAll(ids)) kotlinx.coroutines.delay(POLL_MILLIS)
        }
    }

    private companion object {
        const val CATALOGUE_WAIT_MILLIS = 90_000L
        const val POLL_MILLIS = 1_000L
    }
}

/** Builds the phone-side pieces of a restore from a [Context]. */
internal object AndroidRestore {
    fun target(context: Context, assets: List<MediaAsset>): MediaStoreRestoreTarget {
        val locks = PrivateFolderStore(context.applicationContext)
        return MediaStoreRestoreTarget(context.applicationContext, assets, lockedFolders = { locks.observeLockedFolders().value })
    }

    fun applier(context: Context): AndroidRestoreApplier {
        val app = context.applicationContext
        val runtime = LibraryRuntime.get(app)
        return AndroidRestoreApplier(
            context = app,
            libraryStore = LibraryStore.get(app),
            favoriteStore = FavoriteStore(app),
            sensitiveStore = SensitiveStore(app),
            privateFolderStore = PrivateFolderStore(app),
            geoRepository = GeoMetadataRepository(app),
            recipeStore = RecipeStore(app),
            catalogueIds = { runtime.repository.observeAll().first().mapTo(HashSet()) { it.id.value } },
            requestScan = { runtime.requestScan(false) },
        )
    }

    /** Asks the library to look again, so the restored files appear without waiting for the change observer. */
    fun rescan(context: Context) {
        LibraryRuntime.get(context.applicationContext).requestScan(false)
    }
}
