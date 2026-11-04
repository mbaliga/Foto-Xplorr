package com.fotoxplorr.app.widget

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.MediaCollection
import com.fotoxplorr.core.model.MediaId
import java.time.LocalDateTime
import java.time.ZoneId

/** Shared fixtures for the widget tests: plain values, no Android runtime. */
internal object WidgetFixtures {
    val ZONE: ZoneId = ZoneId.of("Europe/London")

    fun millis(year: Int, month: Int, day: Int, hour: Int = 12, zone: ZoneId = ZONE): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    fun asset(
        id: Long,
        takenMillis: Long = millis(2024, 6, 1),
        folder: String = "Camera",
        video: Boolean = false,
        trashed: Boolean = false,
        name: String = "IMG_$id.jpg",
    ): MediaAsset = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = name,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        bucketName = folder,
        dateTakenMillis = takenMillis,
        dateModifiedSeconds = takenMillis / 1000,
        width = 4000,
        height = 3000,
        sizeBytes = 2_000_000L + id,
        relativePath = "DCIM/$folder/",
        isFavorite = false,
        isTrashed = trashed,
    )

    fun folderKey(asset: MediaAsset): String = folderIdentity(asset).key.value

    fun library(
        assets: List<MediaAsset>,
        favoriteIds: Set<Long> = emptySet(),
        sensitiveIds: Set<Long> = emptySet(),
        archivedIds: Set<Long> = emptySet(),
        lockedFolders: Set<String> = emptySet(),
        collections: List<MediaCollection> = emptyList(),
        hasMediaAccess: Boolean = true,
        identityIds: Set<Long> = emptySet(),
        libraryReadAtMillis: Long? = null,
    ): WidgetLibrary = WidgetLibrary(
        assets = assets,
        favoriteIds = favoriteIds.mapTo(HashSet(), ::MediaId),
        sensitiveIds = sensitiveIds.mapTo(HashSet(), ::MediaId),
        archivedIds = archivedIds.mapTo(HashSet(), ::MediaId),
        lockedFolders = lockedFolders,
        collections = collections,
        hasMediaAccess = hasMediaAccess,
        identityIds = identityIds.mapTo(HashSet(), ::MediaId),
        libraryReadAtMillis = libraryReadAtMillis,
    )

    fun collection(id: String, name: String, members: Set<Long>, created: Long = 0L) =
        MediaCollection(id = id, name = name, mediaIds = members.mapTo(HashSet(), ::MediaId), createdAtMillis = created)
}
