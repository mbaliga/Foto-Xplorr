package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sync.FakeSyncService
import com.fotoxplorr.core.model.MediaId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The icon mapping of Settings, the source guard that keeps rows iconed, and the Services plumbing's pure parts. */
class SettingsIconsTest {

    @Test
    fun `every tab has an icon and no two tabs share one`() {
        val icons = SettingsTab.entries.map { iconFor(it) }
        assertEquals(icons.size, icons.toSet().size)
        assertTrue(SettingsTab.SERVICES in SettingsTab.entries)
    }

    @Test
    fun `every row and every section has an icon and a label`() {
        SettingsRow.entries.forEach { assertNotNull(it.icon.vector); assertTrue(it.label.isNotBlank()) }
        SettingsSection.entries.forEach { assertNotNull(it.icon.vector); assertTrue(it.label.isNotBlank()) }
        assertEquals(SettingsRow.entries.size, SettingsRow.entries.map { it.label }.toSet().size)
    }

    /**
     * The guard for "someone added a row without an icon": a row component called with a bare string
     * (instead of a [SettingsRow]) or a section label without a [SettingsSection] does not pass.
     */
    @Test
    fun `no row component in SettingsTabs is called with a bare label`() {
        val source = listOf("src/main/java", "app/src/main/java").map { File(it, "com/fotoxplorr/app/gallery/SettingsTabs.kt") }
            .first { it.exists() }.readText()
        val bare = Regex("""\b(SwitchRow|ChoiceRow|ActionText|StepperRow|LabelledValue|SectionLabel)\(\s*(label\s*=\s*)?"""")
        assertEquals("a row called with a string, not a SettingsRow/SettingsSection: ${bare.findAll(source).map { it.value }.toList()}", 0, bare.findAll(source).count())
    }

    @Test
    fun `an unselected tab loses its word only on a very narrow screen`() {
        assertTrue(tabShowsLabel(selected = false, screenWidthDp = 360))
        assertTrue(tabShowsLabel(selected = false, screenWidthDp = NARROW_TABS_DP))
        assertFalse(tabShowsLabel(selected = false, screenWidthDp = NARROW_TABS_DP - 1))
        assertTrue(tabShowsLabel(selected = true, screenWidthDp = 200))
    }

    private fun asset(id: Long, bucket: String, path: String) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg", bucketName = bucket, dateTakenMillis = id, dateModifiedSeconds = 0,
        width = 10, height = 10, sizeBytes = 100, relativePath = path, isFavorite = false, isTrashed = false,
    )

    @Test
    fun `sync folders are the library's folders with their counts, by name`() {
        val choices = syncFolderChoices(
            listOf(asset(1, "Zoo", "Pictures/Zoo/"), asset(2, "Camera", "DCIM/Camera/"), asset(3, "Camera", "DCIM/Camera/")),
        )
        assertEquals(listOf("Camera", "Zoo"), choices.map { it.label })
        assertEquals(listOf(2, 1), choices.map { it.photoCount })
    }

    @Test
    fun `the holder builds the sync service once and hands later library lists to it`() {
        var built = 0
        var seen: () -> List<MediaAsset> = { emptyList() }
        val holder = SyncServiceHolder { browsable, _ -> built++; seen = browsable; FakeSyncService() }
        val first = holder.obtain(emptyList(), null)
        val list = listOf(asset(1, "A", "A/"))
        val second = holder.obtain(list, null)
        assertSame(first, second)
        assertEquals(1, built)
        assertSame(list, seen())
    }
}
