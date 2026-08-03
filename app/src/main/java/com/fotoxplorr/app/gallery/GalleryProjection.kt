package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId

internal enum class GallerySection {
    PHOTOS,
    FAVORITES,
    ALBUMS,
    TRASH,
}

internal data class AlbumSummary(
    val key: String,
    val name: String,
    val count: Int,
    val cover: MediaAsset,
)

internal fun visibleAssets(
    assets: List<MediaAsset>,
    favoriteIds: Set<MediaId>,
    section: GallerySection,
    selectedAlbum: String?,
    query: String,
    sort: GallerySort,
    lockedFolders: Set<String> = emptySet(),
    unlockedFolders: Set<String> = emptySet(),
): List<MediaAsset> {
    val trashScoped = if (section == GallerySection.TRASH) {
        assets.filter { it.isTrashed }
    } else {
        assets.filterNot { it.isTrashed }
    }

    val privacyVisible = trashScoped.filter { asset ->
        val folderKey = folderIdentity(asset).key.value
        folderKey !in lockedFolders || folderKey in unlockedFolders
    }

    val scoped = when {
        section == GallerySection.FAVORITES -> privacyVisible.filter { it.id in favoriteIds }
        section == GallerySection.ALBUMS && selectedAlbum != null -> {
            privacyVisible.filter { folderIdentity(it).key.value == selectedAlbum }
        }
        else -> privacyVisible
    }

    val normalizedQuery = query.trim().lowercase()
    val searched = if (normalizedQuery.isEmpty()) {
        scoped
    } else {
        scoped.filter { asset ->
            asset.displayName.lowercase().contains(normalizedQuery) ||
                asset.mimeType.lowercase().contains(normalizedQuery) ||
                folderIdentity(asset).displayName.lowercase().contains(normalizedQuery)
        }
    }

    return when (sort) {
        GallerySort.NEWEST -> searched.sortedWith(
            compareByDescending<MediaAsset> { it.dateTakenMillis }
                .thenByDescending { it.dateModifiedSeconds }
                .thenByDescending { it.id.value },
        )
        GallerySort.OLDEST -> searched.sortedWith(
            compareBy<MediaAsset> { it.dateTakenMillis }
                .thenBy { it.dateModifiedSeconds }
                .thenBy { it.id.value },
        )
        GallerySort.NAME -> searched.sortedWith(
            compareBy<MediaAsset> { it.displayName.lowercase() }
                .thenByDescending { it.dateTakenMillis },
        )
        GallerySort.SIZE -> searched.sortedWith(
            compareByDescending<MediaAsset> { it.sizeBytes }
                .thenBy { it.displayName.lowercase() },
        )
    }
}

internal fun buildAlbumSummaries(
    assets: List<MediaAsset>,
    query: String = "",
): List<AlbumSummary> {
    val normalizedQuery = query.trim().lowercase()
    return assets
        .asSequence()
        .filterNot { it.isTrashed }
        .groupBy { folderIdentity(it).key.value }
        .map { (key, items) ->
            AlbumSummary(
                key = key,
                name = folderIdentity(items.first()).displayName,
                count = items.size,
                cover = items.first(),
            )
        }
        .filter { normalizedQuery.isEmpty() || it.name.lowercase().contains(normalizedQuery) }
        .sortedWith(
            compareByDescending<AlbumSummary> { it.count }
                .thenBy { it.name.lowercase() },
        )
}

internal fun resolveAlbumName(
    bucketName: String?,
    relativePath: String?,
): String = folderIdentity(bucketName, relativePath).displayName
