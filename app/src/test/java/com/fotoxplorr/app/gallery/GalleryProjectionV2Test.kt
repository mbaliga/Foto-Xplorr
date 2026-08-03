package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class GalleryProjectionV2Test {
    private val preferences = GalleryPreferencesState()

    @Test
    fun `timeline groups media by local day newest first`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val august3 = ZonedDateTime.of(2026, 8, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val august2 = ZonedDateTime.of(2026, 8, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val items = listOf(asset(1, august2), asset(2, august3), asset(3, august3 + 1_000))

        val groups = timelineGroups(items, TimelineGrouping.DAY, zone, Locale.ENGLISH)

        assertEquals(listOf("2026-08-03", "2026-08-02"), groups.map { it.key })
        assertEquals(listOf(MediaId(2), MediaId(3)), groups.first().assets.map { it.id })
    }

    @Test
    fun `everyday assets exclude archive trash and locked folders`() {
        val normal = asset(1, 100)
        val archived = asset(2, 200)
        val trashed = asset(3, 300, trashed = true)
        val locked = asset(4, 400, path = "Pictures/Private/")
        val lockedKey = folderIdentity(locked).key.value

        val visible = everydayAssets(
            assets = listOf(normal, archived, trashed, locked),
            archivedIds = setOf(archived.id),
            sensitiveIds = emptySet(),
            lockedFolders = setOf(lockedKey),
            unlockedFolders = emptySet(),
            preferences = preferences,
            query = "",
        )

        assertEquals(listOf(normal), visible)
    }

    @Test
    fun `smart albums identify videos screenshots large files and untagged media`() {
        val video = asset(1, 100, mime = "video/mp4", name = "clip.mp4")
        val screenshot = asset(2, 200, name = "Screenshot_2026.png", path = "Pictures/Screenshots/")
        val large = asset(3, 300, size = 25L * 1024L * 1024L)
        val tagged = asset(4, 400)
        val all = listOf(video, screenshot, large, tagged)
        val tags = mapOf(tagged.id to setOf("family"))

        val videos = smart(SmartAlbum.VIDEOS, all, tags)
        val screenshots = smart(SmartAlbum.SCREENSHOTS, all, tags)
        val largeFiles = smart(SmartAlbum.LARGE_FILES, all, tags)
        val untagged = smart(SmartAlbum.UNTAGGED, all, tags)

        assertEquals(listOf(video), videos)
        assertEquals(listOf(screenshot), screenshots)
        assertEquals(listOf(large), largeFiles)
        assertFalse(tagged in untagged)
        assertTrue(video in untagged)
    }

    @Test
    fun `duplicate candidates require same size dimensions and mime`() {
        val first = asset(1, 100, size = 2_000, width = 800, height = 600)
        val second = asset(2, 200, size = 2_000, width = 800, height = 600)
        val differentDimensions = asset(3, 300, size = 2_000, width = 600, height = 800)

        assertEquals(setOf(first.id, second.id), duplicateCandidateIds(listOf(first, second, differentDimensions)))
    }

    @Test
    fun `sensitive media can be hidden from the timeline`() {
        val normal = asset(1, 100)
        val sensitive = asset(2, 200)

        val visible = everydayAssets(
            assets = listOf(normal, sensitive),
            archivedIds = emptySet(),
            sensitiveIds = setOf(sensitive.id),
            lockedFolders = emptySet(),
            unlockedFolders = emptySet(),
            preferences = preferences.copy(hideSensitive = true),
            query = "",
        )

        assertEquals(listOf(normal), visible)
    }

    private fun smart(
        album: SmartAlbum,
        assets: List<MediaAsset>,
        tags: Map<MediaId, Set<String>>,
    ): List<MediaAsset> = smartAlbumAssets(
        smartAlbum = album,
        assets = assets,
        favoriteIds = emptySet(),
        sensitiveIds = emptySet(),
        archivedIds = emptySet(),
        tagsByMediaId = tags,
        lockedFolders = emptySet(),
        unlockedFolders = emptySet(),
        preferences = preferences,
        nowMillis = 1_000_000,
    )

    private fun asset(
        id: Long,
        taken: Long,
        mime: String = "image/jpeg",
        name: String = "$id.jpg",
        path: String = "DCIM/Camera/",
        size: Long = 1_000,
        width: Int = 100,
        height: Int = 100,
        trashed: Boolean = false,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/$id",
        displayName = name,
        mimeType = mime,
        bucketName = path.trimEnd('/').substringAfterLast('/'),
        bucketId = id,
        dateTakenMillis = taken,
        dateModifiedSeconds = taken / 1_000,
        width = width,
        height = height,
        sizeBytes = size,
        durationMillis = if (mime.startsWith("video/")) 10_000 else 0,
        relativePath = path,
        isFavorite = false,
        isTrashed = trashed,
    )
}
