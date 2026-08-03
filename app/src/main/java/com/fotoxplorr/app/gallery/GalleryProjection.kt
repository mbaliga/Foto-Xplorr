package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId

internal enum class GallerySection {
    PHOTOS,
    FAVORITES,
    ALBUMS,
}

internal data class AlbumSummary(
    val name: String,
    val count: Int,
    val cover: MediaAsset,
)

internal fun visibleAssets(
    assets: List<MediaAsset>,
    favoriteIds: Set<MediaId>,
    section: GallerySection,
    selectedAlbum: String?,
): List<MediaAsset> = when {
    section == GallerySection.FAVORITES -> assets.filter { it.id in favoriteIds }
    section == GallerySection.ALBUMS && selectedAlbum != null -> {
        assets.filter { resolveAlbumName(it.bucketName, it.relativePath) == selectedAlbum }
    }
    else -> assets
}

internal fun buildAlbumSummaries(assets: List<MediaAsset>): List<AlbumSummary> = assets
    .groupBy { resolveAlbumName(it.bucketName, it.relativePath) }
    .map { (name, items) ->
        AlbumSummary(
            name = name,
            count = items.size,
            cover = items.first(),
        )
    }
    .sortedWith(
        compareByDescending<AlbumSummary> { it.count }
            .thenBy { it.name.lowercase() },
    )

internal fun resolveAlbumName(
    bucketName: String?,
    relativePath: String?,
): String {
    val bucket = bucketName?.trim().orEmpty()
    if (bucket.isNotEmpty()) return bucket

    val path = relativePath
        ?.trim()
        ?.trim('/')
        .orEmpty()
    if (path.isNotEmpty()) return path.substringAfterLast('/')

    return "Other"
}
