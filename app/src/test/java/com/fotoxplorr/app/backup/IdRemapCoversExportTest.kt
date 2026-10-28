package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The guard behind [IdRemap]: it lists every key of `LibraryStore.exportJson` that holds ids. A
 * key added to the export and not to that list would keep its OLD ids through a restore, which
 * attaches tags, captions or marks to the wrong photos. So this fills a real store, exports it,
 * remaps every id, and demands that no old id survives anywhere.
 *
 * Robolectric because [LibraryStore] is the real SharedPreferences-backed store and its export is
 * `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdRemapCoversExportTest {
    private val offset = 100_000L

    private fun filledExport(): JsonValue {
        val library = LibraryStore(RuntimeEnvironment.getApplication())
        fun p(n: Long) = MediaId(n)
        library.addTag(setOf(p(1)), "beach")
        library.addAutoTags(p(2), setOf("dog"))
        library.addAutoTags(p(3), setOf("dog"))
        library.removeTag(setOf(p(3)), "dog") // a rejected auto tag
        val holiday = library.createCollection("Holiday")!!
        library.addToCollection(holiday.id, setOf(p(4), p(5)))
        library.setArchived(setOf(p(6)), true)
        library.setArchived(setOf(p(7)), true)
        library.setArchived(setOf(p(7)), false) // ever unarchived
        library.rejectArchiveSuggestions(setOf(p(8)))
        library.setCaption(p(9), "a typed caption")
        library.applyMachineCaption(p(10), "a machine caption")
        library.applyMachineCaption(p(11), "another machine caption")
        library.setCaption(p(11), "") // the person cleared it: suppressed
        return checkNotNull(parseJson(library.exportJson().toString()))
    }

    private fun collectIds(value: JsonValue, key: String?, out: MutableList<Pair<String, Long>>) {
        when (value) {
            is JsonValue.JsonObject -> value.entries.forEach { (k, v) ->
                if (key == "captions" && k.toLongOrNull() != null) out += "captions" to k.toLong()
                collectIds(v, k, out)
            }
            is JsonValue.JsonArray -> value.items.forEach { collectIds(it, key, out) }
            is JsonValue.JsonNumber -> if (key != "schema" && key != "createdAtMillis") out += (key ?: "?") to value.value.toLong()
            else -> Unit
        }
    }

    @Test
    fun `the remap knows every key the library exports`() {
        val keys = (filledExport() as JsonValue.JsonObject).entries.keys
        assertEquals(IdRemap.LIBRARY_KEYS + "schema", keys)
    }

    @Test
    fun `no old id survives a remap of a filled library`() {
        val export = filledExport()
        val before = mutableListOf<Pair<String, Long>>()
        collectIds(export, null, before)
        val keysWithIds = before.map { it.first }.toSet()
        assertTrue("the fixture must put an id under every key: $keysWithIds", keysWithIds.containsAll(IdRemap.LIBRARY_ID_ARRAYS))
        // the per-name objects hold their ids under the tag's name
        assertTrue(keysWithIds.containsAll(setOf("mediaIds", "captions", "beach", "dog")))

        val result = IdRemap.remapLibrary(export, (1L..11L).associateWith { it + offset })
        assertEquals(emptySet<Long>(), result.droppedIds)
        val after = mutableListOf<Pair<String, Long>>()
        collectIds(result.json, null, after)
        assertFalse(after.isEmpty())
        after.forEach { (key, id) -> assertTrue("$key still holds the old id $id", id > offset) }
    }
}
