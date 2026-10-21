package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reads the phone's own stores into the manifest's extra sections ([BackupSections]). Called once
 * at the start of a run. A store that cannot be read fails the run before any pack is written;
 * a backup that quietly left out the tags would be worse than one that did not start.
 *
 * Edit recipes are kept only for the file revision they describe (a recipe saved against an older
 * revision of a photo is already ignored by the editor), and only for photos the library still has.
 */
class BackupSectionSource(
    private val libraryStore: LibraryStore,
    private val favoriteStore: FavoriteStore,
    private val sensitiveStore: SensitiveStore,
    private val privateFolderStore: PrivateFolderStore,
    private val geoRepository: GeoMetadataRepository,
    private val recipeStore: RecipeStore,
) {
    suspend fun gather(assets: List<MediaAsset>, nowMillis: Long): List<ManifestSection> = withContext(Dispatchers.IO) {
        val library = parseJson(libraryStore.exportJson().toString()) ?: throw BackupRefused("Your library could not be read, so the backup was not started.")
        val favorites = favoriteStore.observe().first().mapTo(linkedSetOf()) { it.value }
        val sensitive = sensitiveStore.observe().first().mapTo(linkedSetOf()) { it.value }
        val catalogue = assets.asSequence().filterNot { it.isExternal }.associate {
            it.id.value to MediaIdentity(it.displayName, it.sizeBytes, it.dateTakenMillis, it.relativePath)
        }
        val metadata = MetadataBackup.buildFile(library, favorites, sensitive, catalogue, nowMillis).json

        val revisions = assets.asSequence().filterNot { it.isExternal }.associate { it.id.value to it.dateModifiedSeconds }
        val recipes = recipeStore.stored().mapNotNull { stored ->
            if (revisions[stored.mediaId] != stored.dateModifiedSeconds) {
                null
            } else {
                BackupSections.recipeFromStored(stored.mediaId, stored.dateModifiedSeconds, stored.json)
            }
        }
        BackupSections.build(
            metadata = metadata,
            locks = privateFolderStore.lockRecords().map { BackupSections.Lock(it.folderKey, it.saltBase64, it.hashBase64) },
            pins = geoRepository.manualPins().map { BackupSections.Pin(it.mediaId.value, it.latitude, it.longitude) },
            recipes = recipes,
        )
    }
}
