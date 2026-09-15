package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class GallerySection {
    PHOTOS,
    FAVORITES,
    ALBUMS,
    TRASH,
}

enum class SmartAlbum {
    FAVORITES,
    RECENT,
    VIDEOS,
    SCREENSHOTS,
    ANIMATED,
    LARGE_FILES,
    DUPLICATES,
    SENSITIVE,
    ARCHIVED,
    TRASH,
    UNTAGGED,
}

data class AlbumSummary(
    val key: String,
    val name: String,
    val count: Int,
    val cover: MediaAsset,
    /**
     * The top few photos, newest first, for the fanned album stack. [cover] is always the first
     * of these; it is kept as its own field because plenty of callers want exactly one photo and
     * should not have to reach into a list to get it.
     */
    val covers: List<MediaAsset> = listOf(cover),
)

data class TimelineGroup(
    val key: String,
    val title: String,
    val assets: List<MediaAsset>,
)

data class SmartAlbumSummary(
    val album: SmartAlbum,
    val title: String,
    val subtitle: String,
    val count: Int,
    val cover: MediaAsset?,
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
    val privacyVisible = trashScoped.filter { it.isPrivacyVisible(lockedFolders, unlockedFolders) }
    val scoped = when {
        section == GallerySection.FAVORITES -> privacyVisible.filter { it.id in favoriteIds }
        section == GallerySection.ALBUMS && selectedAlbum != null -> {
            privacyVisible.filter { folderIdentity(it).key.value == selectedAlbum }
        }
        else -> privacyVisible
    }
    return sortAssets(scoped.filterByQuery(query), sort)
}

fun everydayAssets(
    assets: List<MediaAsset>,
    archivedIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    preferences: GalleryPreferencesState,
    query: String,
    tagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
): List<MediaAsset> = sortAssets(
    assets.asSequence()
        .filterNot { it.isTrashed }
        .filterNot { it.id in archivedIds }
        .filter { preferences.showVideos || !it.isVideo }
        .filter { !preferences.hideSensitive || it.id !in sensitiveIds }
        .filter { it.isPrivacyVisible(lockedFolders, unlockedFolders) }
        .filter { it.matchesQuery(query, tagsByMediaId[it.id].orEmpty()) }
        .toList(),
    preferences.sort,
)

fun assetsForAlbum(
    assets: List<MediaAsset>,
    albumKey: String,
    archivedIds: Set<MediaId>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    preferences: GalleryPreferencesState,
    query: String = "",
    tagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
): List<MediaAsset> = sortAssets(
    assets.asSequence()
        .filterNot { it.isTrashed }
        .filterNot { it.id in archivedIds }
        .filter { preferences.showVideos || !it.isVideo }
        .filter { folderIdentity(it).key.value == albumKey }
        .filter { it.isPrivacyVisible(lockedFolders, unlockedFolders) }
        .filter { it.matchesQuery(query, tagsByMediaId[it.id].orEmpty()) }
        .toList(),
    preferences.sort,
)

fun timelineGroups(
    assets: List<MediaAsset>,
    grouping: TimelineGrouping,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<TimelineGroup> {
    if (grouping == TimelineGrouping.NONE) {
        return listOf(TimelineGroup("all", "All media", assets))
    }
    val formatter = when (grouping) {
        TimelineGrouping.DAY -> DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
        TimelineGrouping.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        TimelineGrouping.NONE -> error("Handled above")
    }
    val keyFormatter = when (grouping) {
        TimelineGrouping.DAY -> DateTimeFormatter.ISO_LOCAL_DATE
        TimelineGrouping.MONTH -> DateTimeFormatter.ofPattern("yyyy-MM")
        TimelineGrouping.NONE -> error("Handled above")
    }
    return assets.groupBy { asset ->
        Instant.ofEpochMilli(asset.dateTakenMillis.coerceAtLeast(0L)).atZone(zoneId).toLocalDate()
    }.map { (date, items) ->
        TimelineGroup(
            key = date.format(keyFormatter),
            title = date.format(formatter),
            assets = items,
        )
    }.sortedByDescending { it.key }
}

fun smartAlbumAssets(
    smartAlbum: SmartAlbum,
    assets: List<MediaAsset>,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    archivedIds: Set<MediaId>,
    tagsByMediaId: Map<MediaId, Set<String>>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    preferences: GalleryPreferencesState,
    nowMillis: Long = System.currentTimeMillis(),
): List<MediaAsset> {
    val privacyVisible = assets.filter { it.isPrivacyVisible(lockedFolders, unlockedFolders) }
    val nonTrash = privacyVisible.filterNot { it.isTrashed }
    val duplicateIds = duplicateCandidateIds(nonTrash)
    val scoped = when (smartAlbum) {
        SmartAlbum.FAVORITES -> nonTrash.filter { it.id in favoriteIds }
        SmartAlbum.RECENT -> nonTrash.filter { it.dateTakenMillis >= nowMillis - RECENT_WINDOW_MILLIS }
        SmartAlbum.VIDEOS -> nonTrash.filter { it.isVideo }
        SmartAlbum.SCREENSHOTS -> nonTrash.filter(MediaAsset::isScreenshot)
        SmartAlbum.ANIMATED -> nonTrash.filter { it.isAnimated }
        SmartAlbum.LARGE_FILES -> nonTrash.filter { it.sizeBytes >= LARGE_FILE_THRESHOLD_BYTES }
        SmartAlbum.DUPLICATES -> nonTrash.filter { it.id in duplicateIds }
        SmartAlbum.SENSITIVE -> nonTrash.filter { it.id in sensitiveIds }
        SmartAlbum.ARCHIVED -> nonTrash.filter { it.id in archivedIds }
        SmartAlbum.TRASH -> privacyVisible.filter { it.isTrashed }
        SmartAlbum.UNTAGGED -> nonTrash.filter { tagsByMediaId[it.id].isNullOrEmpty() }
    }
    return sortAssets(
        scoped.filter { preferences.showVideos || !it.isVideo },
        preferences.sort,
    )
}

fun smartAlbumSummaries(
    assets: List<MediaAsset>,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    archivedIds: Set<MediaId>,
    tagsByMediaId: Map<MediaId, Set<String>>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    preferences: GalleryPreferencesState,
): List<SmartAlbumSummary> = SmartAlbum.entries.map { album ->
    val items = smartAlbumAssets(
        smartAlbum = album,
        assets = assets,
        favoriteIds = favoriteIds,
        sensitiveIds = sensitiveIds,
        archivedIds = archivedIds,
        tagsByMediaId = tagsByMediaId,
        lockedFolders = lockedFolders,
        unlockedFolders = unlockedFolders,
        preferences = preferences,
    )
    SmartAlbumSummary(
        album = album,
        title = album.title(),
        subtitle = album.subtitle(),
        count = items.size,
        cover = items.firstOrNull(),
    )
}

internal fun buildAlbumSummaries(
    assets: List<MediaAsset>,
    query: String = "",
): List<AlbumSummary> {
    val normalizedQuery = query.trim().lowercase()
    return assets.asSequence()
        .filterNot { it.isTrashed }
        .groupBy { folderIdentity(it).key.value }
        .map { (key, items) ->
            // Sorted once and reused for both fields: `maxBy` followed by a separate sort would
            // walk the album twice, and on a folder with thousands of photos that is not free.
            val newestFirst = items.sortedByDescending { it.dateTakenMillis }
            AlbumSummary(
                key = key,
                name = folderIdentity(items.first()).displayName,
                count = items.size,
                cover = newestFirst.first(),
                covers = newestFirst.take(ALBUM_STACK_COVERS),
            )
        }
        .filter { normalizedQuery.isEmpty() || it.name.lowercase().contains(normalizedQuery) }
        .sortedWith(compareByDescending<AlbumSummary> { it.count }.thenBy { it.name.lowercase() })
}

fun duplicateCandidateIds(assets: List<MediaAsset>): Set<MediaId> = assets
    .asSequence()
    .filter { it.sizeBytes > 0L && it.width > 0 && it.height > 0 }
    .groupBy { DuplicateKey(it.sizeBytes, it.width, it.height, it.mimeType.lowercase()) }
    .values
    .filter { it.size > 1 }
    .flatten()
    .mapTo(linkedSetOf()) { it.id }

fun sortAssets(assets: List<MediaAsset>, sort: GallerySort): List<MediaAsset> = when (sort) {
    GallerySort.NEWEST -> assets.sortedWith(
        compareByDescending<MediaAsset> { it.dateTakenMillis }
            .thenByDescending { it.dateModifiedSeconds }
            .thenByDescending { it.id.value },
    )
    GallerySort.OLDEST -> assets.sortedWith(
        compareBy<MediaAsset> { it.dateTakenMillis }
            .thenBy { it.dateModifiedSeconds }
            .thenBy { it.id.value },
    )
    GallerySort.NAME -> assets.sortedWith(
        compareBy<MediaAsset> { it.displayName.lowercase() }
            .thenByDescending { it.dateTakenMillis },
    )
    GallerySort.SIZE -> assets.sortedWith(
        compareByDescending<MediaAsset> { it.sizeBytes }
            .thenBy { it.displayName.lowercase() },
    )
}

internal fun resolveAlbumName(
    bucketName: String?,
    relativePath: String?,
): String = folderIdentity(bucketName, relativePath).displayName

fun SmartAlbum.title(): String = when (this) {
    SmartAlbum.FAVORITES -> "Favourites"
    SmartAlbum.RECENT -> "Recently captured"
    SmartAlbum.VIDEOS -> "Videos"
    SmartAlbum.SCREENSHOTS -> "Screenshots"
    SmartAlbum.ANIMATED -> "Animated"
    SmartAlbum.LARGE_FILES -> "Large media"
    SmartAlbum.DUPLICATES -> "Possible duplicates"
    SmartAlbum.SENSITIVE -> "Sensitive"
    SmartAlbum.ARCHIVED -> "Archive"
    SmartAlbum.TRASH -> "Trash"
    SmartAlbum.UNTAGGED -> "Untagged"
}

private fun SmartAlbum.subtitle(): String = when (this) {
    SmartAlbum.FAVORITES -> "Things you marked"
    SmartAlbum.RECENT -> "The last 30 days"
    SmartAlbum.VIDEOS -> "All locally indexed video"
    SmartAlbum.SCREENSHOTS -> "Detected by name or folder"
    SmartAlbum.ANIMATED -> "GIF, animated WebP and AVIF"
    SmartAlbum.LARGE_FILES -> "20 MB and above"
    SmartAlbum.DUPLICATES -> "Exact size and dimensions"
    SmartAlbum.SENSITIVE -> "Content marked sensitive"
    SmartAlbum.ARCHIVED -> "Hidden from the timeline"
    SmartAlbum.TRASH -> "Android system trash"
    SmartAlbum.UNTAGGED -> "Media without custom tags"
}

/**
 * internal, not file-private, for the same reason [isScreenshot] is: the Tidy up queue must apply
 * the exact rule every other browsing surface applies, or a locked folder's contents surface
 * there with thumbnails and filenames.
 */
internal fun MediaAsset.isPrivacyVisible(
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
): Boolean {
    val key = folderIdentity(this).key.value
    return key !in lockedFolders || key in unlockedFolders
}

private fun MediaAsset.matchesQuery(query: String, tags: Set<String>): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return true
    return displayName.lowercase().contains(normalized) ||
        mimeType.lowercase().contains(normalized) ||
        folderIdentity(this).displayName.lowercase().contains(normalized) ||
        tags.any { it.lowercase().contains(normalized) }
}

private fun List<MediaAsset>.filterByQuery(query: String): List<MediaAsset> =
    filter { it.matchesQuery(query, emptySet()) }

/**
 * internal, not file-private: the archive review queue asks the same question when deciding
 * whether a photo is an old screenshot worth offering to tidy away, and two copies of "what
 * counts as a screenshot" would eventually disagree about the same photo on two screens.
 */
internal fun MediaAsset.isScreenshot(): Boolean =
    displayName.contains("screenshot", ignoreCase = true) ||
        folderIdentity(this).displayName.contains("screenshot", ignoreCase = true)

private data class DuplicateKey(
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

private const val RECENT_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val LARGE_FILE_THRESHOLD_BYTES = 20L * 1024L * 1024L

/** How many photos an album stack fans out. Matches AlbumStack's own layer cap. */
private const val ALBUM_STACK_COVERS = 3
