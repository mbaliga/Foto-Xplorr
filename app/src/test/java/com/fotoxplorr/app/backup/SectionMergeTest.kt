package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wiped or new phone has no tags, locks, pins or edits. A backup run on it before the person
 * restores must not turn the backup's sections into none: the previous snapshot's are folded into
 * the new ones, the phone's own state winning wherever both have an entry.
 */
class SectionMergeTest {
    private fun identity(id: Long, name: String = "IMG_$id.jpg") = MediaIdentity(name, 1_000 + id, 5_000 + id, "DCIM/Camera/")

    private fun library(tags: Map<String, List<Long>> = emptyMap(), captions: Map<Long, String> = emptyMap()): JsonValue {
        val tagText = tags.entries.joinToString(",") { (name, ids) -> "\"$name\":[${ids.joinToString(",")}]" }
        val captionText = captions.entries.joinToString(",") { (id, text) -> "\"$id\":\"$text\"" }
        return checkNotNull(parseJson("""{"schema":1,"tags":{$tagText},"captions":{$captionText}}"""))
    }

    private fun sections(
        tags: Map<String, List<Long>> = emptyMap(),
        captions: Map<Long, String> = emptyMap(),
        favourites: Set<Long> = emptySet(),
        sensitive: Set<Long> = emptySet(),
        identities: Map<Long, MediaIdentity> = (tags.values.flatten() + captions.keys + favourites + sensitive).associateWith { identity(it) },
        locks: List<BackupSections.Lock> = emptyList(),
        pins: List<BackupSections.Pin> = emptyList(),
        recipes: List<BackupSections.Recipe> = emptyList(),
    ): List<ManifestSection> {
        val file = MetadataBackup.buildFile(library(tags, captions), favourites, sensitive, identities, 1_000).json
        return BackupSections.build(file, locks, pins, recipes)
    }

    private fun List<ManifestSection>.named(name: String) = first { it.name == name }.data

    private fun metadata(result: List<ManifestSection>): MetadataBackup.MetadataFile =
        (MetadataBackup.readTree(result.named(BackupSections.METADATA)) as MetadataBackup.FileRead.Usable).file

    private fun tagIds(file: MetadataBackup.MetadataFile, tag: String): Set<Long> =
        file.envelope["library"]["tags"][tag].asArray().map { it.asLong() }.toSet()

    private fun ids(file: MetadataBackup.MetadataFile, key: String): Set<Long> = file.envelope[key].asArray().map { it.asLong() }.toSet()

    private val lock = BackupSections.Lock("path:dcim/private", "c2FsdA==", "aGFzaA==")
    private val pin = BackupSections.Pin(3, 10.0, 20.0)
    private val recipe = BackupSections.Recipe(4, 99, checkNotNull(parseJson("""{"look":"warm"}""")))

    private val full = sections(
        tags = mapOf("beach" to listOf(1L, 2L)),
        captions = mapOf(1L to "old caption"),
        favourites = setOf(1L),
        sensitive = setOf(2L),
        locks = listOf(lock),
        pins = listOf(pin),
        recipes = listOf(recipe),
    )

    @Test
    fun `a phone with nothing leaves the backup with everything it had`() {
        val empty = sections()
        val merged = SectionMerge.union(previous = full, current = empty)

        val file = metadata(merged)
        assertEquals(setOf(1L, 2L), tagIds(file, "beach"))
        assertEquals(setOf(1L), ids(file, "favoriteIds"))
        assertEquals(setOf(2L), ids(file, "sensitiveIds"))
        assertEquals("old caption", file.envelope["library"]["captions"]["1"].asString())
        assertEquals(identity(1), file.identity!![1L])
        assertEquals(listOf("path:dcim/private"), BackupSections.locks(merged.named(BackupSections.LOCKS)).map { it.folderKey })
        assertEquals(listOf(3L), BackupSections.pins(merged.named(BackupSections.PINS)).map { it.mediaId })
        assertEquals(listOf(4L), BackupSections.recipes(merged.named(BackupSections.RECIPES)).map { it.mediaId })
        assertEquals(
            listOf(BackupSections.METADATA, BackupSections.LOCKS, BackupSections.PINS, BackupSections.RECIPES),
            merged.map { it.name },
        )
    }

    @Test
    fun `the phone's own entry wins where both snapshots have one, and its additions join`() {
        val now = sections(
            tags = mapOf("beach" to listOf(2L, 3L), "dog" to listOf(3L)),
            captions = mapOf(1L to "new caption"),
            locks = listOf(BackupSections.Lock("path:dcim/private", "bmV3", "bmV3")),
            pins = listOf(BackupSections.Pin(3, 50.0, 60.0), BackupSections.Pin(8, 1.0, 2.0)),
            recipes = listOf(BackupSections.Recipe(4, 100, checkNotNull(parseJson("""{"look":"cool"}""")))),
        )
        val merged = SectionMerge.union(previous = full, current = now)

        val file = metadata(merged)
        assertEquals(setOf(1L, 2L, 3L), tagIds(file, "beach"))
        assertEquals(setOf(3L), tagIds(file, "dog"))
        assertEquals("new caption", file.envelope["library"]["captions"]["1"].asString())
        val lock = BackupSections.locks(merged.named(BackupSections.LOCKS)).single()
        assertEquals("bmV3", lock.saltBase64)
        val pins = BackupSections.pins(merged.named(BackupSections.PINS)).associateBy { it.mediaId }
        assertEquals(setOf(3L, 8L), pins.keys)
        assertEquals(50.0, pins.getValue(3).latitude, 1e-9)
        val recipes = BackupSections.recipes(merged.named(BackupSections.RECIPES))
        assertEquals(1, recipes.size)
        assertEquals(100L, recipes.single().dateModifiedSeconds)
    }

    @Test
    fun `an id that is the same photo in both snapshots stays one id`() {
        val before = sections(tags = mapOf("beach" to listOf(1L)))
        val now = sections(tags = mapOf("dog" to listOf(1L)))
        val file = metadata(SectionMerge.union(before, now))
        assertEquals(setOf(1L), tagIds(file, "beach"))
        assertEquals(setOf(1L), tagIds(file, "dog"))
        assertEquals(setOf(1L), file.identity!!.keys)
    }

    @Test
    fun `an id that means two different photos is not merged into one`() {
        // Photo 5 on the old phone was A.jpg; on this phone id 5 is Z.jpg.
        val before = sections(tags = mapOf("old" to listOf(5L)), identities = mapOf(5L to identity(5, "A.jpg")))
        val now = sections(tags = mapOf("new" to listOf(5L)), identities = mapOf(5L to identity(5, "Z.jpg")))
        val file = metadata(SectionMerge.union(before, now))

        assertEquals("this phone's photo keeps its id", setOf(5L), tagIds(file, "new"))
        assertEquals("Z.jpg", file.identity!![5L]!!.displayName)
        val oldIds = tagIds(file, "old")
        assertEquals(1, oldIds.size)
        assertNotEquals("the old photo's tag must not land on this phone's photo 5", setOf(5L), oldIds)
        assertEquals("A.jpg", file.identity!![oldIds.single()]!!.displayName)
    }

    @Test
    fun `a renumbered id stays clear of the ids the manifest's entries use`() {
        val before = sections(tags = mapOf("old" to listOf(5L)), identities = mapOf(5L to identity(5, "A.jpg")))
        val now = sections(tags = mapOf("new" to listOf(5L)), identities = mapOf(5L to identity(5, "Z.jpg")))
        val file = metadata(SectionMerge.union(before, now, avoidIdsAbove = 9_000))
        assertTrue(tagIds(file, "old").single() > 9_000)
    }

    @Test
    fun `a section only one snapshot has is carried`() {
        val onlyBefore = SectionMerge.union(previous = listOf(ManifestSection("extra", JsonValue.JsonString("x"))), current = sections())
        assertEquals(listOf(BackupSections.METADATA, BackupSections.LOCKS, BackupSections.PINS, BackupSections.RECIPES, "extra"), onlyBefore.map { it.name })
        assertEquals(sections().map { it.name }, SectionMerge.union(emptyList(), sections()).map { it.name })
    }

    @Test
    fun `a previous tags section that cannot be read is dropped and the phone's own is kept`() {
        val damaged = listOf(ManifestSection(BackupSections.METADATA, JsonValue.JsonString("not a tags file")))
        val now = sections(tags = mapOf("dog" to listOf(3L)))
        val merged = SectionMerge.union(damaged, now)
        assertEquals(setOf(3L), tagIds(metadata(merged), "dog"))
    }

    @Test
    fun `the phone being behind the backup is told apart from the phone being ahead or different`() {
        assertTrue(SectionMerge.isFewer(sections(), full))
        assertTrue("some of it gone", SectionMerge.isFewer(sections(tags = mapOf("beach" to listOf(1L, 2L)), favourites = setOf(1L), sensitive = setOf(2L)), full))
        assertFalse("the same", SectionMerge.isFewer(full, full))
        assertFalse("more", SectionMerge.isFewer(full, sections()))
        // Fewer tags but a lock the backup lacks: different, not behind.
        assertFalse(SectionMerge.isFewer(sections(locks = listOf(lock, BackupSections.Lock("b", "c", "d"))), full))
        assertTrue(SectionMerge.sizeOf(sections()).isEmpty())
        assertNotNull(SectionMerge.sizeOf(full))
    }

    @Test
    fun `restore folds the earlier snapshot in only when the newest holds less`() {
        val newestEmpty = sections()
        val loadedBehind = loaded(newestEmpty)
        val folded = RestoreSet.from(loadedBehind, earlierSections = full)
        assertTrue(folded.usesEarlierSnapshot)
        assertTrue(folded.info.earlierSnapshotUsed)
        assertEquals(1, folded.info.folderLocks)
        assertEquals(1, folded.info.pins)
        assertEquals(1, folded.info.recipes)
        assertNotNull(RestoreMetadata.file(folded))

        // Nothing earlier, or an earlier one that holds no more: the newest stands as written.
        assertFalse(RestoreSet.from(loadedBehind, earlierSections = null).usesEarlierSnapshot)
        assertFalse(RestoreSet.from(loaded(full), earlierSections = sections()).usesEarlierSnapshot)
        assertNull(RestoreSet.from(loadedBehind).section("nothing-like-this"))
        assertEquals(0, RestoreSet.from(loadedBehind).info.folderLocks)
    }

    private fun loaded(sections: List<ManifestSection>) = LoadedSet(
        listing = listOf(DestinationFile("manifest-0002.fzbm", 10)),
        manifest = BackupManifest(ManifestHeader(2, 1, "t", BackupFormat.MIN_ITERATIONS, 128), emptyList(), emptyList(), sections),
        skippedManifests = 0,
        highestGeneration = 2,
        recovered = emptyList(),
        leftovers = emptyList(),
        reservedPackNumbers = emptySet(),
    )
}
