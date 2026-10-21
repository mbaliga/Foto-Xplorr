package com.fotoxplorr.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestoreSetTest {
    private lateinit var dir: File

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-restoreset-test").toFile()
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun `the first card describes the backup from its manifest`() {
        val made = RestoreFixtures.backup(
            dir,
            count = 5,
            transform = { list -> list.mapIndexed { i, c -> if (i == 4) c.copy(mimeType = "video/mp4", displayName = "clip.mp4") else c } },
        )
        val info = made.set().info

        assertEquals(1, info.generation)
        assertEquals("9.9.9", info.appVersion)
        assertEquals(4, info.photos)
        assertEquals(1, info.videos)
        assertEquals(5, info.files)
        assertEquals(5 * 300L, info.totalBytes)
        assertEquals(0, info.missing)
        assertNotNull(info.metadata)
        assertEquals(1, info.metadata!!.collections)
        assertEquals(1, info.metadata!!.tags)
        assertEquals(1, info.metadata!!.captions)
        assertEquals(1, info.metadata!!.favorites)
        assertEquals(1, info.metadata!!.sensitive)
        assertEquals(0, info.folderLocks + info.pins + info.recipes)
    }

    @Test
    fun `a pack that is not in the folder marks its files missing from the listing`() {
        val made = RestoreFixtures.backup(dir, count = 9)
        val pack = made.packs()[0]
        val inPack = made.set().entries.count { it.packName == pack }
        File(dir, pack).delete()

        val set = made.set()
        assertEquals(inPack, set.entries.count { EntryFlag.MISSING in it.flags })
        assertTrue(set.entries.filter { it.packName == pack }.all { EntryFlag.MISSING in it.flags })
        assertEquals(inPack, set.info.missing)
    }

    @Test
    fun `a changed file with two records restores only the newest`() {
        val old = entry(1, "IMG.jpg", "pack-0001.fzbp")
        val new = entry(5, "IMG.jpg", "pack-0002.fzbp")
        val other = entry(2, "other.jpg", "pack-0001.fzbp")
        val loaded = LoadedSet(
            listing = listOf(DestinationFile("pack-0001.fzbp", 10), DestinationFile("pack-0002.fzbp", 10)),
            manifest = BackupManifest(ManifestHeader(1, 1, "t", BackupFormat.MIN_ITERATIONS, 128), listOf(old, other), emptyList(), emptyList()),
            skippedManifests = 0,
            highestGeneration = 1,
            recovered = listOf(PackIndex(ManifestPack("pack-0002.fzbp", 10, 1), listOf(new))),
            leftovers = emptyList(),
            reservedPackNumbers = emptySet(),
        )
        val set = RestoreSet.from(loaded)
        assertEquals(listOf(2L, 5L), set.entries.map { it.entryId })
    }

    @Test
    fun `packs with no manifest restore their photos and carry no tags`() {
        val e = entry(1, "a.jpg", "pack-0001.fzbp")
        val loaded = LoadedSet(
            listing = listOf(DestinationFile("pack-0001.fzbp", 10)),
            manifest = null,
            skippedManifests = 1,
            highestGeneration = 1,
            recovered = listOf(PackIndex(ManifestPack("pack-0001.fzbp", 10, 1), listOf(e))),
            leftovers = emptyList(),
            reservedPackNumbers = emptySet(),
        )
        val set = RestoreSet.from(loaded)
        assertFalse(set.isEmpty)
        assertNull(set.header)
        assertNull(set.info.metadata)
        assertNull(set.info.generation)
    }

    @Test
    fun `a backup with no photos and no tags holds nothing to restore, whatever else it carries`() {
        val manifest = BackupManifest(
            ManifestHeader(1, 1, "t", BackupFormat.MIN_ITERATIONS, 128),
            emptyList(),
            emptyList(),
            BackupSections.build(com.fotoxplorr.app.editor.JsonValue.JsonNull, listOf(BackupSections.Lock("path:a", "c2FsdA==", "aGFzaA==")), emptyList(), emptyList()),
        )
        assertTrue(RestoreSet(emptyList(), manifest).isEmpty)
    }

    @Test
    fun `a folder with nothing in it restores nothing`() {
        val loaded = LoadedSet(emptyList(), null, 0, 0, emptyList(), emptyList(), emptySet())
        assertTrue(RestoreSet.from(loaded).isEmpty)
    }

    private fun entry(id: Long, name: String, pack: String) = ManifestEntry(
        entryId = id, mediaId = id, displayName = name, relativePath = "DCIM/Camera/", mimeType = "image/jpeg", sizeBytes = 10,
        dateTakenMillis = 0, dateModifiedSeconds = 0, sha256 = "00".repeat(32), quickHash = null, packName = pack,
    )
}
