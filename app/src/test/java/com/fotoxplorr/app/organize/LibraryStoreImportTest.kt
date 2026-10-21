package com.fotoxplorr.app.organize

import com.fotoxplorr.app.backup.ImportMode
import com.fotoxplorr.app.backup.IdRemap
import com.fotoxplorr.app.backup.MediaIdentity
import com.fotoxplorr.app.backup.MetadataBackup
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.core.model.MediaId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a restore does to the real, SharedPreferences-backed library.
 *
 * The old import cleared the whole preferences file and rewrote it from the file, with nothing
 * said first. These pin the replacement: a merge only ever adds, a replace is a deliberate act, a
 * file that fails a check leaves the library byte-for-byte as it was, and ids that were remapped
 * on the way in are the ids that end up stored.
 *
 * Robolectric rather than a fake because the thing under test is the preferences read-modify-write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStoreImportTest {

    private fun store() = LibraryStore(RuntimeEnvironment.getApplication())

    private fun p(n: Long) = MediaId(n)

    /** A library filled through every kind of write, so a key the import forgets shows up. */
    private fun LibraryStore.fill() {
        addTag(setOf(p(1)), "beach")
        addAutoTags(p(2), setOf("dog"))
        addAutoTags(p(3), setOf("dog"))
        removeTag(setOf(p(3)), "dog")
        val holiday = createCollection("Holiday")!!
        addToCollection(holiday.id, setOf(p(4), p(5)))
        setArchived(setOf(p(6)), true)
        setArchived(setOf(p(7)), true)
        setArchived(setOf(p(7)), false)
        rejectArchiveSuggestions(setOf(p(8)))
        setCaption(p(9), "a typed caption")
        applyMachineCaption(p(10), "a machine caption")
        applyMachineCaption(p(11), "another")
        setCaption(p(11), "")
    }

    /** The state as plain sets, comparable between two stores. */
    private fun LibraryStore.snapshot(): Any = observe().value.let {
        listOf(
            it.collections.map { c -> Triple(c.name, c.mediaIds, c.createdAtMillis) }.toSet(),
            it.tagsByMediaId, it.archivedIds, it.autoTagsByMediaId, it.captionByMediaId, it.machineCaptionIds,
            it.everUnarchivedIds, it.rejectedArchiveSuggestionIds, it.rejectedAutoTagsByMediaId,
            it.suppressedMachineCaptionIds,
        )
    }

    private fun clearPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("foto_xplorr_library", 0).edit().clear().commit()
    }

    @Test
    fun `a merge adds to what is here and removes nothing`() {
        val library = store()
        library.addTag(setOf(p(1)), "beach")
        library.setCaption(p(1), "mine")
        val backup = JSONObject(
            """{"schema":1,"tags":{"beach":[2],"sky":[3]},"captions":{"1":"theirs","2":"two"},
                "collections":[{"id":"x","name":"Trip","createdAtMillis":1,"mediaIds":[2]}]}""",
        )

        assertTrue(library.importJson(backup).isSuccess)

        val state = library.observe().value
        assertEquals(setOf(p(1), p(2)), state.tagsByMediaId.filterValues { "beach" in it }.keys)
        assertEquals(setOf("sky"), state.tagsFor(p(3)))
        assertEquals("a caption written here is not overwritten", "mine", state.captionFor(p(1)))
        assertEquals("two", state.captionFor(p(2)))
        assertEquals(listOf("Trip"), state.collections.map { it.name })
    }

    @Test
    fun `a merge never wipes preferences the export does not carry`() {
        val library = store()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("foto_xplorr_library", 0)
        prefs.edit().putString("some_other_key", "kept").commit()
        assertTrue(library.importJson(JSONObject("""{"schema":1,"tags":{"a":[1]}}""")).isSuccess)
        assertEquals("kept", prefs.getString("some_other_key", null))
    }

    @Test
    fun `a replace puts back exactly the backup`() {
        val library = store()
        library.fill()
        val backup = library.exportJson()
        library.addTag(setOf(p(50)), "added after the backup")
        library.setCaption(p(51), "caption after the backup")
        library.createCollection("Later")

        assertTrue(library.importJson(backup, ImportMode.REPLACE).isSuccess)

        val state = library.observe().value
        assertFalse(state.tagsByMediaId.containsKey(p(50)))
        assertEquals("", state.captionFor(p(51)))
        assertEquals(listOf("Holiday"), state.collections.map { it.name })
        assertEquals(setOf("beach"), state.tagsFor(p(1)))
        assertEquals(setOf("dog"), state.autoTagsFor(p(2)))
        assertEquals(setOf("dog"), state.rejectedAutoTagsFor(p(3)))
    }

    @Test
    fun `merging a backup into the library it came from changes nothing`() {
        val library = store()
        library.fill()
        val before = library.snapshot()
        assertTrue(library.importJson(library.exportJson()).isSuccess)
        assertEquals(before, library.snapshot())
        // twice, too: a restore tapped again is harmless
        assertTrue(library.importJson(library.exportJson()).isSuccess)
        assertEquals(before, library.snapshot())
    }

    @Test
    fun `a replace of a filled library with its own export keeps every kind of memory`() {
        val library = store()
        library.fill()
        val before = library.snapshot()
        assertTrue(library.importJson(library.exportJson(), ImportMode.REPLACE).isSuccess)
        assertEquals("a key the import forgot would show here", before, library.snapshot())
    }

    @Test
    fun `restoring into a library that lost its data brings back every kind of memory`() {
        val source = store()
        source.fill()
        val before = source.snapshot()
        val backup = source.exportJson()
        clearPreferences()
        val fresh = store()
        assertTrue(fresh.observe().value.collections.isEmpty())

        assertTrue(fresh.importJson(backup).isSuccess)

        // collection ids are random and are part of what is restored; everything else compares by value
        assertEquals(before, fresh.snapshot())
    }

    @Test
    fun `a file that fails a check changes nothing, in either mode`() {
        val library = store()
        library.fill()
        val before = library.snapshot()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("foto_xplorr_library", 0)
        val keysBefore = prefs.all.toMap()

        val bad = listOf(
            """{"schema":2,"tags":{}}""",
            """{"tags":{"a":[1]}}""",
            """{"schema":1,"tags":[1,2]}""",
            """{"schema":1,"tags":{"a":"b"}}""",
            """{"schema":1,"captions":{"1":7}}""",
            """{"schema":1,"collections":["x"]}""",
            """{"schema":1,"archivedIds":{}}""",
        )
        for (text in bad) for (mode in ImportMode.values()) {
            assertTrue("$text should have failed in $mode", library.importJson(JSONObject(text), mode).isFailure)
            assertEquals("$text changed the library in $mode", before, library.snapshot())
            assertEquals("$text changed the stored preferences in $mode", keysBefore, prefs.all.toMap())
        }
    }

    @Test
    fun `a remapped file lands on the ids of this phone`() {
        // Phone A's library, saved with identity...
        val source = store()
        source.addTag(setOf(p(1), p(2)), "beach")
        source.setCaption(p(2), "two")
        val phoneA = mapOf(
            1L to MediaIdentity("a.jpg", 10, 100, "DCIM/"),
            2L to MediaIdentity("b.jpg", 20, 200, "DCIM/"),
        )
        val file = MetadataBackup.buildFile(
            parseJson(source.exportJson().toString())!!, emptySet(), emptySet(), phoneA, 1,
        )
        val text = file.json.stringify()
        clearPreferences()

        // ...restored on phone B, where the same photos have other ids.
        val phoneB = mapOf(
            701L to phoneA.getValue(1L),
            702L to phoneA.getValue(2L),
            703L to MediaIdentity("c.jpg", 30, 300, "DCIM/"),
        )
        val read = MetadataBackup.read(text) as MetadataBackup.FileRead.Usable
        val prepared = MetadataBackup.prepare(read.file, phoneB)
        val target = store()
        assertTrue(target.importJson(JSONObject(prepared.library.stringify())).isSuccess)

        val state = target.observe().value
        assertEquals(setOf("beach"), state.tagsFor(p(701)))
        assertEquals(setOf("beach"), state.tagsFor(p(702)))
        assertEquals("two", state.captionFor(p(702)))
        assertTrue("nothing lands on the old ids", state.tagsFor(p(1)).isEmpty() && state.tagsFor(p(2)).isEmpty())
        assertTrue(state.tagsFor(p(703)).isEmpty())
    }

    @Test
    fun `the store's schema and the backup's are the same number`() {
        val exported = store().exportJson()
        assertEquals(MetadataBackup.LIBRARY_SCHEMA, exported.getInt("schema"))
        // and the remap and the merge both understand every key the real export writes
        val keys = exported.keys().asSequence().toSet()
        assertEquals(IdRemap.LIBRARY_KEYS + "schema", keys)
    }
}
